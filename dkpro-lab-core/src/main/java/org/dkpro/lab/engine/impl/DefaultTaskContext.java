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

import static org.dkpro.lab.Util.close;
import static org.dkpro.lab.engine.impl.ImportUtil.extractConstraints;
import static org.dkpro.lab.storage.StorageService.CONTEXT_ID_SCHEME;
import static org.dkpro.lab.storage.StorageService.LATEST_CONTEXT_SCHEME;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Map;

import org.dkpro.lab.conversion.ConversionService;
import org.dkpro.lab.engine.LifeCycleManager;
import org.dkpro.lab.engine.TaskContext;
import org.dkpro.lab.engine.TaskContextFactory;
import org.dkpro.lab.engine.TaskExecutionService;
import org.dkpro.lab.logging.LoggingService;
import org.dkpro.lab.storage.StorageService;
import org.dkpro.lab.storage.StreamReader;
import org.dkpro.lab.storage.StreamWriter;
import org.dkpro.lab.storage.TaskContextNotFoundException;
import org.dkpro.lab.storage.UnresolvedImportException;
import org.dkpro.lab.storage.StorageService.AccessMode;
import org.dkpro.lab.storage.StorageService.StorageKey;
import org.dkpro.lab.task.TaskContextMetadata;
import org.springframework.dao.DataAccessResourceFailureException;

public class DefaultTaskContext
	implements TaskContext
{
	private final TaskContextFactory owner;

	private LoggingService loggingService;
	private StorageService storageService;
	private ConversionService conversionService;
	private LifeCycleManager lifeCycleManager;
	private TaskContextMetadata metadata;
	private TaskExecutionService executionService;

	public DefaultTaskContext(final TaskContextFactory aOwner)
	{
		owner = aOwner;
		metadata = new TaskContextMetadata();
	}

	@Override
	public TaskContextFactory getTaskContextFactory()
	{
		return owner;
	}
	
	@Override
	public String getId()
	{
		return getMetadata().getId();
	}

	@Override
	public void message(String msg)
	{
		getLoggingService().message(getId(), msg);
	}

	@Override
	public void error(String aMsg)
	{
		getLoggingService().error(getId(), aMsg, null);
	}
	
	@Override
	public void error(String aMsg, Throwable aCause)
	{
		getLoggingService().error(getId(), aMsg, aCause);
	}
	
	@Override
	public void destroy()
	{
		owner.destroyContext(this);
	}

	public void setStorageService(StorageService aStorage)
	{
		storageService = aStorage;
	}

	@Override
	public StorageService getStorageService()
	{
		return storageService;
	}

	public void setLoggingService(LoggingService aLoggingService)
	{
		loggingService = aLoggingService;
	}

	@Override
	public LoggingService getLoggingService()
	{
		return loggingService;
	}
	
	public void setConversionService(ConversionService aConversionService)
    {
        conversionService = aConversionService;
    }

    @Override
    public ConversionService getConversionService()
    {
        return conversionService;
    }

	public void setLifeCycleManager(LifeCycleManager aLifeCycleManager)
	{
		lifeCycleManager = aLifeCycleManager;
	}

	@Override
	public LifeCycleManager getLifeCycleManager()
	{
		return lifeCycleManager;
	}
	
	public void setExecutionService(TaskExecutionService aExecutionService)
	{
		executionService = aExecutionService;
	}

	@Override
	public TaskExecutionService getExecutionService()
	{
		return executionService;
	}

	@Override
	public void storeBinary(String aPath, StreamWriter aStreamWriter)
	{
		// Data is always stored to the current context. No need to resolve.
		getStorageService().storeBinary(getId(), aPath, aStreamWriter);
	}

	@Override
	public void storeBinary(String aPath, InputStream aStream)
	{
		// Data is always stored to the current context. No need to resolve.
		getStorageService().storeBinary(getId(), aPath, aStream);
	}

	@Override
	public boolean containsKey(String aPath)
	{
		return getStorageService().containsKey(getId(), aPath)
				|| getMetadata().getImports().containsKey(aPath);
	}

	@Override
	public <T extends StreamReader> T retrieveBinary(String aPath, T aReader)
	{
		StorageKey key = resolve(aPath, AccessMode.READONLY, false);
		return getStorageService().retrieveBinary(key.contextId, key.key, aReader);
	}

	public void setMetadata(TaskContextMetadata aMetadata)
	{
		metadata = aMetadata;
	}

	@Override
	public TaskContextMetadata getMetadata()
	{
		return metadata;
	}

	@Deprecated
    @Override
	public File getStorageLocation(String aKey, AccessMode aMode)
	{
        StorageKey key;

        StorageService storage = getStorageService();
        Map<String, String> imports = getMetadata().getImports();

        if (storage.containsKey(getId(), aKey)) {
            // If the context contains the key, we do nothing. Locally available data always
            // supersedes imported data.
            key = new StorageKey(getId(), aKey);
        }
        else if (imports.containsKey(aKey)) {
            URI uri;
            try {
                uri = new URI(imports.get(aKey));
            }
            catch (URISyntaxException e) {
                throw new DataAccessResourceFailureException("Imported key [" + aKey
                        + "] resolves to illegal URL [" + imports.get(aKey) + "]", e);
            }

            if ("file".equals(uri.getScheme()) && new File(uri).isDirectory()) {
                if (aMode == AccessMode.READONLY) {
                    return new File(uri);
                }
                else {
                    // Here we should probably just copy the imported folder into the context
                    throw new DataAccessResourceFailureException("READWRITE access of imported " +
                            "folders is not implemented yet.");
                }
            }
            else {
                key = resolve(aKey, aMode, true);
            }
        }
        else {
            key = resolve(aKey, aMode, true);
        }

        return getStorageService().getStorageFolder(key.contextId, key.key);
    }
	
	@Override
	public File getFile(String aKey, AccessMode aMode)
	{
        StorageKey key;

        StorageService storage = getStorageService();
        Map<String, String> imports = getMetadata().getImports();

        if (storage.containsKey(getId(), aKey)) {
            // If the context contains the key, we do nothing. Locally available data always
            // supersedes imported data.
            key = new StorageKey(getId(), aKey);
        }
        else if (imports.containsKey(aKey)) {
            URI uri;
            try {
                uri = new URI(imports.get(aKey));
            }
            catch (URISyntaxException e) {
                throw new DataAccessResourceFailureException("Imported key [" + aKey
                        + "] resolves to illegal URL [" + imports.get(aKey) + "]", e);
            }

            if ("file".equals(uri.getScheme()) && new File(uri).isFile()) {
                if (aMode == AccessMode.READONLY) {
                    return new File(uri);
                }
                else {
                    // Here we should probably just copy the imported file into the context
                    throw new DataAccessResourceFailureException("READWRITE access of imported " +
                            "files is not implemented yet.");
                }
            }
            else {
                key = resolve(aKey, aMode, true);
            }
        }
        else {
            key = resolve(aKey, aMode, true);
        }

        File file = getStorageService().locateKey(key.contextId, key.key);
        
        if (file.exists() && !file.isFile()) {
            throw new DataAccessResourceFailureException("Key [" + aKey
                    + "] resolves to [" + file + "] which is not a file."); 
        }
        
        return file;
    }
	
	@Override
	public File getFolder(String aKey, AccessMode aMode)
	{
        StorageKey key;

        StorageService storage = getStorageService();
        Map<String, String> imports = getMetadata().getImports();

        if (storage.containsKey(getId(), aKey)) {
            // If the context contains the key, we do nothing. Locally available data always
            // supersedes imported data.
            key = new StorageKey(getId(), aKey);
        }
        else if (imports.containsKey(aKey)) {
            URI uri;
            try {
                uri = new URI(imports.get(aKey));
            }
            catch (URISyntaxException e) {
                throw new DataAccessResourceFailureException("Imported key [" + aKey
                        + "] resolves to illegal URL [" + imports.get(aKey) + "]", e);
            }

            if ("file".equals(uri.getScheme()) && new File(uri).isDirectory()) {
                if (aMode == AccessMode.READONLY) {
                    return new File(uri);
                }
                else {
                    // Here we should probably just copy the imported folder into the context
                    throw new DataAccessResourceFailureException("READWRITE access of imported " +
                            "folders is not implemented yet.");
                }
            }
            else {
                key = resolve(aKey, aMode, true);
            }
        }
        else {
            key = resolve(aKey, aMode, true);
        }

        File folder = getStorageService().locateKey(key.contextId, key.key);
        
        if (!folder.exists()) {
            folder.mkdirs();
        }
        
        if (!folder.isDirectory()) {
            throw new DataAccessResourceFailureException("Key [" + aKey
                    + "] resolves to [" + folder + "] which is not a folder."); 
        }
        
        return folder;
	}

	@Override
	public TaskContextMetadata resolve(URI aUri)
	{
		StorageService storage = getStorageService();
		if (LATEST_CONTEXT_SCHEME.equals(aUri.getScheme())) {
			try {
				return storage.getLatestContext(aUri.getAuthority(), extractConstraints(aUri));
			}
			catch (TaskContextNotFoundException e) {
                throw new UnresolvedImportException(this, aUri.toString(), e);
			}
		}
		else if (CONTEXT_ID_SCHEME.equals(aUri.getScheme())) {
			try {
				return storage.getContext(aUri.getAuthority());
			}
			catch (TaskContextNotFoundException e) {
                throw new UnresolvedImportException(this, aUri.toString(), e);
			}
		}
		else {
			throw new DataAccessResourceFailureException("Unknown scheme in import ["+aUri+"]");
		}
	}

	public
	StorageKey resolve(String aKey, AccessMode aMode, boolean aAllowMissing)
	{
		StorageService storage = getStorageService();
		Map<String, String> imports = getMetadata().getImports();

		if (storage.containsKey(getId(), aKey)) {
			// If the context contains the key, we do nothing. Locally available data always
			// supersedes imported data.
			return new StorageKey(getId(), aKey);
		}
		else if (imports.containsKey(aKey)) {
			URI uri;
			try {
				uri = new URI(imports.get(aKey));
			}
			catch (URISyntaxException e) {
				throw new DataAccessResourceFailureException("Imported key [" + aKey
						+ "] resolves to illegal URL [" + imports.get(aKey) + "]", e);
			}

			// Try resolving by ID or by type/constraints
			StorageKey key = null;
			if (CONTEXT_ID_SCHEME.equals(uri.getScheme()) || LATEST_CONTEXT_SCHEME.equals(uri.getScheme())) {
				TaskContextMetadata meta = resolve(uri);
				key = new StorageKey(meta.getId(), uri.getPath());
			}

			// If the resource is imported from another context and will be modified it has to
			// be copied into the current context. The storage may decide though not to copy
			// data at this point if it can assure a copy-on-write behavior. E.g. it may copy
			// imported storage folders now but imported stream-access (files) keys later.
			if (key != null) {
				switch (aMode) {
				case ADD_ONLY:
				case READWRITE:
					storage.copy(getId(), aKey, key, aMode);
					return new StorageKey(getId(), aKey);
				case READONLY:
					return key;
				}
			}

			// If this is an external URL, copy it to the current context and then return a location
			// in the current context.
			InputStream is = null;
			try {
				is = uri.toURL().openStream();
				storage.storeBinary(getId(), aKey, is);
				return new StorageKey(getId(), aKey);
			}
			catch (MalformedURLException e) {
				throw new DataAccessResourceFailureException("Imported external key [" + aKey
						+ "] resolves to illegal URL [" + uri + "]", e);
			}
			catch (IOException e) {
				throw new DataAccessResourceFailureException(
						"Unable to read data for external key [" + aKey + "] from [" + uri + "]", e);
			}
			finally {
				close(is);
			}
		}
		else if (aAllowMissing) {
			return new StorageKey(getId(), aKey);
		}

		throw new DataAccessResourceFailureException("No resource bound to key [" + aKey + "]");
	}
}
