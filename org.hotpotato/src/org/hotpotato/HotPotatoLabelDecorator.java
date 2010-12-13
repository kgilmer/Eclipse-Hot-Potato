/*
 * Copyright (c) 2010, Ken Gilmer
 * All rights reserved.
 * 
 * Redistribution and use in source and binary forms, with or without modification, are permitted provided that the following conditions are met:
 *
 * Redistributions of source code must retain the above copyright notice, this list of conditions and the following disclaimer.
 * Redistributions in binary form must reproduce the above copyright notice, this list of conditions and the following disclaimer in the documentation and/or other materials provided with the distribution.
 * Neither the name of Ken Gilmer or the names of its contributors may be used to endorse or promote products derived from this software without specific prior written permission.
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY 
 * AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR 
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON 
 * ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED 
 * OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package org.hotpotato;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IResourceChangeEvent;
import org.eclipse.core.resources.IResourceChangeListener;
import org.eclipse.core.resources.IResourceDelta;
import org.eclipse.core.resources.IResourceDeltaVisitor;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.IJobChangeEvent;
import org.eclipse.core.runtime.jobs.IJobChangeListener;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.jface.resource.ImageDescriptor;
import org.eclipse.jface.viewers.IDecoration;
import org.eclipse.jface.viewers.ILabelProviderListener;
import org.eclipse.jface.viewers.ILightweightLabelDecorator;
import org.eclipse.jface.viewers.LabelProviderChangedEvent;
import org.eclipse.ui.IDecoratorManager;
import org.eclipse.ui.PlatformUI;
import org.hotpotato.preferences.PreferenceConstants;

/**
 * A label decorator and resource change listener than decorates Workbench resource icons based on file age.
 * @author kgilmer
 *
 */
public class HotPotatoLabelDecorator implements ILightweightLabelDecorator,
		IResourceChangeListener {

	private List listeners;
	private ImageDescriptor cold;
	private ImageDescriptor hot;
	private ImageDescriptor warm;
	private final IDecoratorManager dm;
	private boolean shutdown = false;

	private final long timeMultiplier;
	private long DELTA_HOT;
	private long DELTA_WARM;
	private long DELTA_COOL;
	private IPreferenceStore prefs;
	private final int region;

	public HotPotatoLabelDecorator() {
		prefs = Activator.getDefault().getPreferenceStore();
		
		timeMultiplier = prefs.getInt(PreferenceConstants.PREF_TIME_MULTIPLIER) * 1000;
		DELTA_HOT = timeMultiplier * 2;
		DELTA_WARM = timeMultiplier * 10;
		DELTA_COOL = timeMultiplier * 100;
		
		region = prefs.getInt(PreferenceConstants.PREF_DECORATOR_REGION);
		
		listeners = new ArrayList();
		cold = Activator.getDefault().getImageRegistry()
				.getDescriptor(Activator.IMAGE_COLD);
		hot = Activator.getDefault().getImageRegistry()
				.getDescriptor(Activator.IMAGE_HOT);
		warm = Activator.getDefault().getImageRegistry()
				.getDescriptor(Activator.IMAGE_WARM);

		dm = PlatformUI.getWorkbench().getDecoratorManager();
		ResourcesPlugin.getWorkspace().addResourceChangeListener(this);

		Job j = new TemporalUpdateJob();
		j.addJobChangeListener(new RescheduleJobChangeListener());
		j.schedule(DELTA_HOT);
	}

	@Override
	public void addListener(ILabelProviderListener listener) {
		listeners.add(listener);
	}

	@Override
	public void dispose() {
		ResourcesPlugin.getWorkspace().removeResourceChangeListener(this);
		shutdown = true;
	}

	@Override
	public boolean isLabelProperty(Object element, String property) {
		return false;
	}

	@Override
	public void removeListener(ILabelProviderListener listener) {
		listeners.remove(listener);
	}

	@Override
	public void decorate(Object element, IDecoration decoration) {
		if (element instanceof IFile) {
			IFile file = (IFile) element;

			long fa = file.getLocalTimeStamp();
			long delta = System.currentTimeMillis() - fa;

			if (delta < DELTA_HOT) {
				decoration.addOverlay(hot, region);
			} else if (delta < DELTA_WARM) {
				decoration.addOverlay(warm, region);
			} else if (delta < DELTA_COOL) {
				decoration.addOverlay(cold, region);
			}
		}
	}

	@Override
	public void resourceChanged(IResourceChangeEvent event) {
		if (event.getType() != IResourceChangeEvent.POST_CHANGE) {
			return;
		}

		IResourceDelta rootDelta = event.getDelta();

		final ArrayList changed = new ArrayList();
		IResourceDeltaVisitor visitor = new IResourceDeltaVisitor() {
			public boolean visit(IResourceDelta delta) {
				// only interested in changed resources (not added or removed)
				if (delta.getKind() != IResourceDelta.CHANGED)
					return true;
				// only interested in content changes
				if ((delta.getFlags() & IResourceDelta.CONTENT) == 0)
					return true;
				IResource resource = delta.getResource();
				if (resource.getType() == IResource.FILE) {
					changed.add(resource);
				}
				return true;
			}
		};
		try {
			rootDelta.accept(visitor);

			for (Iterator i = listeners.iterator(); i.hasNext();) {
				ILabelProviderListener lpl = (ILabelProviderListener) i.next();

				lpl.labelProviderChanged(new LabelProviderChangedEvent(this,
						changed.toArray()));

			}
		} catch (CoreException e) {
			//Something strange happend, stopped periodic updates.
			shutdown = true;
			Activator.getDefault().getLog().log(new Status(IStatus.ERROR, Activator.PLUGIN_ID, "Error occured during resource change update.", e));
		}
	}

	private class TemporalUpdateJob extends Job {

		public TemporalUpdateJob() {
			super("Updating resource time decorators.");
			this.setUser(false);
			this.setPriority(Job.DECORATE);
		}

		@Override
		protected IStatus run(IProgressMonitor monitor) {
			PlatformUI.getWorkbench().getDisplay().syncExec(new Runnable() {

				@Override
				public void run() {
					dm.update("org.hotpotato.decorator");
				}
			});

			return Status.OK_STATUS;
		}

	}

	private class RescheduleJobChangeListener implements IJobChangeListener {

		@Override
		public void aboutToRun(IJobChangeEvent event) {
			// TODO Auto-generated method stub

		}

		@Override
		public void awake(IJobChangeEvent event) {
			// TODO Auto-generated method stub

		}

		@Override
		public void done(IJobChangeEvent event) {
			if (!shutdown) {
				Job j = new TemporalUpdateJob();
				j.addJobChangeListener(new RescheduleJobChangeListener());
				j.schedule(DELTA_HOT);
			}
		}

		@Override
		public void running(IJobChangeEvent event) {
		}

		@Override
		public void scheduled(IJobChangeEvent event) {
		}

		@Override
		public void sleeping(IJobChangeEvent event) {
		}
	}
}
