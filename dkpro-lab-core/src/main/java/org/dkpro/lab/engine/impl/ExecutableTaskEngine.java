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
package org.dkpro.lab.engine.impl;

import org.dkpro.lab.engine.ExecutionException;
import org.dkpro.lab.engine.LifeCycleException;
import org.dkpro.lab.engine.TaskContext;
import org.dkpro.lab.engine.TaskContextFactory;
import org.dkpro.lab.engine.TaskExecutionEngine;
import org.dkpro.lab.storage.UnresolvedImportException;
import org.dkpro.lab.task.ExecutableTask;
import org.dkpro.lab.task.Task;

public class ExecutableTaskEngine
    implements TaskExecutionEngine
{
    private TaskContextFactory contextFactory;

    @Override
    public String run(Task aConfiguration)
        throws ExecutionException, LifeCycleException
    {
        if (!(aConfiguration instanceof ExecutableTask)) {
            throw new ExecutionException("This engine can only execute ["
                    + ExecutableTask.class.getName() + "]");
        }

        // Create persistence service for injection into analysis components
        TaskContext ctx = null;
        try {
            ctx = contextFactory.createContext(aConfiguration);

            // Now the setup is complete
            ctx.getLifeCycleManager().initialize(ctx, aConfiguration);

            // Start recording
            ctx.getLifeCycleManager().begin(ctx, aConfiguration);

            try {
                ((ExecutableTask) aConfiguration).execute(ctx);
            }
            catch (LifeCycleException e) {
                ctx.getLifeCycleManager().fail(ctx, aConfiguration, e);
                throw e;
            }
            catch (UnresolvedImportException e) {
                // HACK - pass unresolved import exceptions up to the outer batch task
                ctx.getLifeCycleManager().fail(ctx, aConfiguration, e);
                throw e;
            }
            catch (Throwable e) {
                ctx.getLifeCycleManager().fail(ctx, aConfiguration, e);
                throw new ExecutionException(e);
            }

            aConfiguration.markExecuted();
            
            // End recording (here the reports will nbe done)
            ctx.getLifeCycleManager().complete(ctx, aConfiguration);

            return ctx.getId();
        }
        finally {
            if (ctx != null) {
                ctx.getLifeCycleManager().destroy(ctx, aConfiguration);
            }
        }
    }

    @Override
    public void setContextFactory(TaskContextFactory aContextFactory)
    {
        contextFactory = aContextFactory;
    }
    
    @Override
    public TaskContextFactory getContextFactory()
    {
        return contextFactory;
    }
}
