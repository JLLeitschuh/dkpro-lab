/*******************************************************************************
 * Copyright 2011
 * Ubiquitous Knowledge Processing (UKP) Lab
 * Technische Universität Darmstadt
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 ******************************************************************************/
package de.tudarmstadt.ukp.dkpro.lab.task.impl;

import static de.tudarmstadt.ukp.dkpro.lab.engine.impl.ImportUtil.extractConstraints;
import static de.tudarmstadt.ukp.dkpro.lab.storage.StorageService.CONTEXT_ID_SCHEME;
import static de.tudarmstadt.ukp.dkpro.lab.storage.StorageService.LATEST_CONTEXT_SCHEME;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Queue;
import java.util.Set;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.dao.DataAccessResourceFailureException;

import de.tudarmstadt.ukp.dkpro.lab.ProgressMeter;
import de.tudarmstadt.ukp.dkpro.lab.Util;
import de.tudarmstadt.ukp.dkpro.lab.engine.LifeCycleManager;
import de.tudarmstadt.ukp.dkpro.lab.engine.TaskContext;
import de.tudarmstadt.ukp.dkpro.lab.engine.TaskContextFactory;
import de.tudarmstadt.ukp.dkpro.lab.engine.TaskExecutionEngine;
import de.tudarmstadt.ukp.dkpro.lab.engine.TaskExecutionService;
import de.tudarmstadt.ukp.dkpro.lab.engine.impl.DefaultTaskContext;
import de.tudarmstadt.ukp.dkpro.lab.engine.impl.DefaultTaskContextFactory;
import de.tudarmstadt.ukp.dkpro.lab.engine.impl.ImportUtil;
import de.tudarmstadt.ukp.dkpro.lab.logging.LoggingService;
import de.tudarmstadt.ukp.dkpro.lab.storage.StorageService;
import de.tudarmstadt.ukp.dkpro.lab.storage.TaskContextNotFoundException;
import de.tudarmstadt.ukp.dkpro.lab.storage.UnresolvedImportException;
import de.tudarmstadt.ukp.dkpro.lab.storage.impl.PropertiesAdapter;
import de.tudarmstadt.ukp.dkpro.lab.task.ConfigurationAware;
import de.tudarmstadt.ukp.dkpro.lab.task.Dimension;
import de.tudarmstadt.ukp.dkpro.lab.task.FixedSizeDimension;
import de.tudarmstadt.ukp.dkpro.lab.task.ParameterSpace;
import de.tudarmstadt.ukp.dkpro.lab.task.Task;
import de.tudarmstadt.ukp.dkpro.lab.task.TaskContextMetadata;
import de.tudarmstadt.ukp.dkpro.lab.task.TaskFactory;
import de.tudarmstadt.ukp.dkpro.lab.task.UnfulfillablePrerequisiteException;

public class BatchTask
	extends ExecutableTaskBase
	implements ConfigurationAware
{
	private Log log = LogFactory.getLog(getClass());

	public static enum ExecutionPolicy { USE_EXISTING, ASK_EXISTING, RUN_AGAIN }

	public static final String SUBTASKS_KEY = "Subtasks";

	private Set<Task> tasks = new HashSet<Task>();
	private ParameterSpace parameterSpace;
	private ExecutionPolicy executionPolicy = ExecutionPolicy.RUN_AGAIN;
	private Map<String, Object> inheritedConfig;
	private Set<String> inheritedScope;

	{
		// Just to make sure there is one run if no parameter space is set.
		parameterSpace = new ParameterSpace(Dimension.create("__DUMMY__", 1));
	}

	public void setParameterSpace(ParameterSpace aParameterSpace)
	{
		parameterSpace = aParameterSpace;
	}

	public void setExecutionPolicy(ExecutionPolicy aPolicy)
	{
		executionPolicy = aPolicy;
	}

	public void addTask(Task aTask)
	{
		tasks.add(aTask);
	}

	public Set<Task> getTasks()
	{
		return Collections.unmodifiableSet(tasks);
	}

	public void setTasks(Set<Task> aTasks)
	{
		tasks = new HashSet<Task>(aTasks);
	}
	
	@Override
	public void setConfiguration(Map<String, Object> aConfig)
	{
		parameterSpace.reset();
		inheritedConfig = aConfig;
	}
	
	public void setScope(Set<String> aScope) 
	{
		inheritedScope = aScope;
	}
	
	@Override
	public void execute(TaskContext aContext)
		throws Exception
	{
		// Try to calculate the parameter space size.
		int estimatedSize = 1;
		for (Dimension<?> d : parameterSpace.getDimensions()) {
			if (d instanceof FixedSizeDimension) {
				FixedSizeDimension fsd = (FixedSizeDimension) d;
				if (fsd.size() > 0) {
					estimatedSize *= fsd.size();
				}
			}
		}
		ProgressMeter progress = new ProgressMeter(estimatedSize);
		
		Map<String, Object> executedSubtasks = new LinkedHashMap<String, Object>();
		for (Map<String, Object> config : parameterSpace) {
			if (inheritedConfig != null) {
				for (Entry<String, Object> e : inheritedConfig.entrySet()) {
					if (!config.containsKey(e.getKey())) {
						config.put(e.getKey(), e.getValue());
					}
				}
			}
			
			log.info("== Running new configuration ["+aContext.getId()+"] ==");
			List<String> keys = new ArrayList<String>(config.keySet());
			for (String key : keys) {
				log.info("["+key+"]: ["+StringUtils.abbreviateMiddle(Util.toString(config.get(key)), "…", 150)+"]");
			}

			if (log.isTraceEnabled()) {
				for (String est : executedSubtasks.keySet()) {
					log.trace("-- Already executed: "+est);
				}
			}

			Set<String> scope = new HashSet<String>();
			if (inheritedScope != null) {
				scope.addAll(inheritedScope);
			}

			configureTasks(aContext, config, scope);

			Queue<Task> queue = new LinkedList<Task>(tasks);
			Set<Task> loopDetection = new HashSet<Task>();

			List<UnresolvedImportException> deferralReasons = new ArrayList<UnresolvedImportException>();
			while (!queue.isEmpty()) {
				Task task = queue.poll();

				TaskExecutionService execService = aContext.getExecutionService();

				// Check if the task was already executed
				TaskContextMetadata existing = getExistingExecution(aContext, task, config, 
						executedSubtasks.keySet());
				if (existing != null) {
					log.debug("Using existing execution [" + existing.getId() + "]");
					executedSubtasks.put(existing.getId(), "");
					scope.add(existing.getId());
					loopDetection.clear();
					deferralReasons.clear();
					continue;
				}

				try {
					log.info("Executing task ["+task.getType()+"]");
					TaskExecutionEngine engine = execService.createEngine(task);
					engine.setContextFactory(new ScopedTaskContextFactory(execService
							.getContextFactory(), config, scope));
					String uuid = engine.run(task);
					executedSubtasks.put(uuid, "");
					scope.add(uuid);
					loopDetection.clear();
					deferralReasons.clear();
				}
				catch (UnresolvedImportException e) {
					log.debug("Deferring execution of task ["+task.getType()+"]: "+e.getMessage());
					queue.add(task);
					if (loopDetection.contains(task)) {
						StringBuilder details = new StringBuilder();
						for (UnresolvedImportException r : deferralReasons) {
                            details.append("\n -");
                            details.append(r.getMessage());
						}
						
						throw new UnfulfillablePrerequisiteException("Unable to continue as all of the remaining tasks have unfulfilled prerequisites.\nDetails:" + details);
					}
					deferralReasons.add(e);
					loopDetection.add(task);
					continue;
				}
			}
			
			progress.next();
			log.info("Completed configuration " + progress);
		}

		// Set the subtask property and persist again, so the property is available to reports
		setAttribute(SUBTASKS_KEY, executedSubtasks.keySet().toString());
		persist(aContext);
	}

	/**
	 * Locate the latest task execution compatible with the given task configuration.
	 * 
	 * @param aContext the context of the current batch task.
	 * @param aType the type of the task context to find.
	 * @param aDiscriminators the discriminators of the task context to find.
	 * @param aConfig the current parameter configuration.
	 * @throws TaskContextNotFoundException if a matching task context could not be found.
	 * @see ImportUtil#matchConstraints(Map, Map, boolean)
	 */
	private TaskContextMetadata getLatestExecution(TaskContext aContext, String aType,
			Map<String, String> aDiscriminators, Map<String, Object> aConfig)
	{
		// Convert parameter values to strings
		Map<String, String >config = new HashMap<String, String>();
		for (Entry<String, Object> e : aConfig.entrySet()) {
			config.put(e.getKey(), Util.toString(e.getValue()));
		}

		StorageService storage = aContext.getStorageService();
		List<TaskContextMetadata> metas = storage.getContexts(aType, aDiscriminators);
		for (TaskContextMetadata meta : metas) {
			Map<String, String> discriminators = storage.retrieveBinary(meta.getId(),
					DISCRIMINATORS_KEY, new PropertiesAdapter()).getMap();
			// Check if the task is compatible with the current configuration. To do this, we
			// interpret the discriminators as constraints on the current configuration.
			if (ImportUtil.matchConstraints(discriminators, config, false)) {
				return meta;
			}
		}
		throw ImportUtil.createContextNotFoundException(aType, aDiscriminators);

	}

	/**
	 * Locate the latest task execution compatible with the given task configuration.
	 * 
	 * @param aContext the context of the current batch task.
	 * @param aTask the the task whose task context should be found.
	 * @param aConfig the current parameter configuration.
	 * @return {@code null} if the context could not be found.
	 */
	private TaskContextMetadata getExistingExecution(TaskContext aContext, Task aTask,
			Map<String, Object> aConfig, Set<String> aScope)
	{
		// Batch tasks are always run again since we do not store discriminators for them
		if (aTask instanceof BatchTask) {
			return null;
		}
		
		try {
			TaskContextMetadata meta = getLatestExecution(aContext, aTask.getType(), aTask
					.getDescriminators(), aConfig);

			// If the task was already executed within the scope of this aggregate, do not execute
			// it again. Catching this here saves us from running tasks with the same configuration
			// more than once per aggregate.
			if (aScope.contains(meta.getId())) {
				return meta;
			}

			switch (executionPolicy) {
			case RUN_AGAIN:
				// Always run the task again
				return null;
			case USE_EXISTING:
				// If the task was ever executed, do not run it again.
				return meta;
			case ASK_EXISTING:
				if (ask(meta)) {
					// Execute again - act as if the context was not found
					return null;
				}
				else {
					// Use existing context
					return meta;
				}
			default:
				throw new IllegalStateException("Unknown executionPolicy ["+executionPolicy+"]");
			}
		}
		catch (TaskContextNotFoundException e) {
			// Task context not found in storage
			return null;
		}
	}

	private boolean ask(TaskContextMetadata aMeta)
	{
		try {
			boolean execute = true;
			InputStreamReader converter = new InputStreamReader(System.in);
			BufferedReader in = new BufferedReader(converter);
			String line = "";
			while (line != null) {
				System.out.println("\n\n[" + aMeta.getType() + "] has already been executed in" +
				" this configuration. Do you wish to execute it again? (y/n)");
				line = in.readLine().toLowerCase();
				if ("y".equals(line)) {
					execute = true;
					break;
				}
				if ("n".equals(line)) {
					execute = false;
					break;
				}
			}
			return execute;
		}
		catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	private void configureTasks(TaskContext aContext, Map<String, Object> aConfig, Set<String> aScope)
	{
		for (Task task : tasks) {
			if (task instanceof BatchTask) {
				((BatchTask) task).setScope(aScope);
			}
			TaskFactory.configureTask(task, aConfig);
		}
	}

	private class ScopedTaskContextFactory extends DefaultTaskContextFactory
	{
		private DefaultTaskContextFactory contextFactory;
		private Map<String, Object> config;
		private Set<String> scope;

		public ScopedTaskContextFactory(TaskContextFactory aContextFactory,
				Map<String, Object> aConfig, Set<String> aScope)
		{
			contextFactory = (DefaultTaskContextFactory) aContextFactory;
			config = aConfig;
			scope = aScope;
		}

		@Override
		protected TaskContext createContext(TaskContextMetadata aMetadata)
		{
			ScopedTaskContext ctx = new ScopedTaskContext(contextFactory);
			ctx.setExecutionService(getExecutionService());
			ctx.setLifeCycleManager(getLifeCycleManager());
			ctx.setStorageService(getStorageService());
			ctx.setLoggingService(getLoggingService());
			ctx.setMetadata(aMetadata);
			ctx.setConfig(config);
			ctx.setScope(scope);
			return ctx;
		}

		@Override
		public void registerContext(TaskContext aContext)
		{
			contextFactory.registerContext(aContext);
		}

		@Override
		public void unregisterContext(TaskContext aContext)
		{
			contextFactory.unregisterContext(aContext);
		}

		@Override
		public String getId()
		{
			return contextFactory.getId();
		}

		@Override
		public LifeCycleManager getLifeCycleManager()
		{
			return contextFactory.getLifeCycleManager();
		}

		@Override
		public LoggingService getLoggingService()
		{
			return contextFactory.getLoggingService();
		}

		@Override
		public StorageService getStorageService()
		{
			return contextFactory.getStorageService();
		}
		
		@Override
		public TaskExecutionService getExecutionService()
		{
			return contextFactory.getExecutionService();
		}
	}

	private class ScopedTaskContext extends DefaultTaskContext
	{
		private Map<String, Object> config;
		private Set<String> scope;

		public ScopedTaskContext(TaskContextFactory aOwner)
		{
			super(aOwner);
		}

		public void setConfig(Map<String, Object> aConfig)
		{
			config = aConfig;
		}

		public void setScope(Set<String> aScope)
		{
			scope = aScope;
		}

		@Override
		public TaskContextMetadata resolve(URI aUri)
		{
			TaskContextMetadata meta;
			StorageService storage = getStorageService();
			if (LATEST_CONTEXT_SCHEME.equals(aUri.getScheme())) {
				Map<String, String> constraints = extractConstraints(aUri);
				try {
					meta = getLatestExecution(this, aUri.getAuthority(), constraints, config);
				}
				catch (TaskContextNotFoundException e) {
				    throw new UnresolvedImportException(this, aUri.toString(), e);
				}
			}
			else if (CONTEXT_ID_SCHEME.equals(aUri.getScheme())) {
				try {
					meta = storage.getContext(aUri.getAuthority());
				}
				catch (TaskContextNotFoundException e) {
                    throw new UnresolvedImportException(this, aUri.toString(), e);
				}
			}
			else {
				throw new DataAccessResourceFailureException("Unknown scheme in import ["+aUri+"]");
			}

			if (!scope.contains(meta.getId())) {
                throw new UnresolvedImportException(this, aUri.toString(), "Resolved context ["
                        + meta.getId() + "] not in scope " + scope);
			}

			return meta;
		}
	}
}
