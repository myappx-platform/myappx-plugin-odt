/**********************************************************************
 * This file is part of iDempiere ERP Open Source                      *
 * http://www.idempiere.org                                            *
 *                                                                     *
 * Copyright (C) Contributors                                          *
 *                                                                     *
 * This program is free software; you can redistribute it and/or       *
 * modify it under the terms of the GNU General Public License         *
 * as published by the Free Software Foundation; either version 2      *
 * of the License, or (at your option) any later version.              *
 *                                                                     *
 * This program is distributed in the hope that it will be useful,     *
 * but WITHOUT ANY WARRANTY; without even the implied warranty of      *
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the        *
 * GNU General Public License for more details.                        *
 *                                                                     *
 * You should have received a copy of the GNU General Public License   *
 * along with this program; if not, write to the Free Software         *
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston,          *
 * MA 02110-1301, USA.                                                 *
 *                                                                     *
 * Contributors:                                                       *
 * - ken.longnan@gmail.com                                             *
 **********************************************************************/

package org.idempiere.myappx.plugin.odt;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.logging.Level;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.adempiere.plugin.utils.Incremental2PackActivator;
import org.adempiere.plugin.utils.Version;
import org.adempiere.util.ServerContext;
import org.compiere.Adempiere;
import org.compiere.model.MClient;
import org.compiere.model.MSession;
import org.compiere.model.Query;
import org.compiere.model.ServerStateChangeEvent;
import org.compiere.model.ServerStateChangeListener;
import org.compiere.model.SystemIDs;
import org.compiere.model.X_AD_Package_Imp;
import org.compiere.model.X_AD_Package_Imp_Proc;
import org.compiere.util.AdempiereSystemError;
import org.compiere.util.CLogger;
import org.compiere.util.CacheMgt;
import org.compiere.util.Env;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import org.idempiere.myappx.plugin.odt.model.MKSODTPackage;
import org.idempiere.myappx.plugin.odt.model.ODTXmlHelper;
import org.idempiere.myappx.plugin.odt.model.X_KS_ODTVersion;
import org.idempiere.myappx.plugin.odt.service.ODTInstallEngine;
import org.idempiere.myappx.plugin.odt.service.ODTVersionCheck;
import org.idempiere.myappx.plugin.odt.util.ODTLog;

public class ODT2PackActivator extends Incremental2PackActivator {
	/** {@code ODT2Pack-{major.minor.patch}_{ClientValue}_{Description}.zip} */
	private static final String ODT2PACK_PREFIX = "ODT2Pack-";

	protected final static CLogger logger = CLogger.getCLogger(ODT2PackActivator.class.getName());
	private File currentFile;
	private HashMap<String, URL> file2urlMap = new HashMap<String, URL>();
	
	@Override
	public String getName() {
		if (currentFile != null)
			return currentFile.getName();
		else
			return context.getBundle().getSymbolicName();
	}
	
	protected void preInstallPackage() {
		// to be override in sub class
	}
	protected void postInstallPackage() {
		// to be override in sub class
	}

	private void installPackage() {
		ODTLog.info(logger, ODTLog.MOD_2PACK, "ODT2Pack starting | " + ODTLog.kvs("bundle", getName()));
		
		ODTLog.info(logger, ODTLog.MOD_2PACK, "Phase 1/3: pre-install | " + ODTLog.kvs("bundle", getName()));
		preInstallPackage();
		
		ODTLog.info(logger, ODTLog.MOD_2PACK, "Phase 2/3: pack-in | " + ODTLog.kvs("bundle", getName()));
		packInAll(); 		 // ODTPackage*.xml + 2Pack_*.zip + ODT2Pack-*.zip
		
		ODTLog.info(logger, ODTLog.MOD_2PACK, "Phase 3/3: post-install | " + ODTLog.kvs("bundle", getName()));
		postInstallPackage();
		
		ODTLog.info(logger, ODTLog.MOD_2PACK, "ODT2Pack done | " + ODTLog.kvs("bundle", getName()));
	}
	
	private void packInAll() {
		ODTLog.info(logger, ODTLog.MOD_2PACK, "packInAll: ODTPackage*.xml | " + ODTLog.kvs("bundle", getName()));
		packInODT();

		// https://wiki.idempiere.org/en/Developing_Plug-Ins_-_2Pack_-_Pack_In/Out
		ODTLog.info(logger, ODTLog.MOD_2PACK, "packInAll: 2Pack_*.zip | " + ODTLog.kvs("bundle", getName()));
		packIn2Pack();      

		// https://wiki.idempiere.org/en/NF5.1_Automatic_External_Packin
		ODTLog.info(logger, ODTLog.MOD_2PACK, "packInAll: ODT2Pack-*.zip | " + ODTLog.kvs("bundle", getName()));
		packInFolder();     
	}

	private void packInODT() {
		Enumeration<URL> urls = context.getBundle().findEntries("/META-INF", "ODTPackage*.xml", false);
		if (urls == null)
			return;

		List<URL> urlsToProcess = new ArrayList<>();
		while (urls.hasMoreElements()) {
			urlsToProcess.add(urls.nextElement());
		}

		if (urlsToProcess.isEmpty()) {
			setSummary(Level.INFO, "No ODT package XML files to process from bundle:" + getName());
			return;
		}

		Collections.sort(urlsToProcess, new Comparator<URL>() {
			@Override
			public int compare(URL u1, URL u2) {
				return new File(u1.getFile()).getName().compareTo(new File(u2.getFile()).getName());
			}
		});

		for (URL configURL : urlsToProcess) {
			currentFile = new File(configURL.getFile());
			ODTLog.info(logger, ODTLog.MOD_2PACK, "Installing ODT package XML | "
					+ ODTLog.kvs("file", currentFile.getName()));
			if (!odtInstall(configURL)) {
				String msg = "Failed application of " + currentFile.getName();
				addLog(Level.WARNING, msg);
				if (getProcessInfo() != null) {
					getProcessInfo().setError(true);
					getProcessInfo().setSummary("@Error@: " + msg);
				}
				break;
			}
			addLog(Level.INFO, "Successful application of " + currentFile.getName());
			currentFile = null;
		}
	}

	private void packIn2Pack() {
		String where = "Name=? AND PK_Status = 'Completed successfully'";
		Query q = new Query(Env.getCtx(), X_AD_Package_Imp.Table_Name,
				where.toString(), null);
		q.setParameters(new Object[] { getName() });
		List<X_AD_Package_Imp> pkgs = q.list();
		List<String> installedVersions = new ArrayList<String>();
		if (pkgs != null && !pkgs.isEmpty()) {
			for(X_AD_Package_Imp pkg : pkgs) {
				String packageVersionPart = pkg.getPK_Version();
				String[] part = packageVersionPart.split("[.]");
				if (part.length > 3 && (packageVersionPart.indexOf(".v") > 0 || packageVersionPart.indexOf(".qualifier") > 0)) {
					packageVersionPart = part[0]+"."+part[1]+"."+part[2];
				}
				installedVersions.add(packageVersionPart);				
			}
		}
		
		packIn(installedVersions);
		afterPackIn();
	}

	/** @return {@code major.minor.patch} from {@code ODT2Pack-major.minor.patch_...}, or null when the name does not match */
	private static String odt2PackVersion(String fileName) {
		if (fileName == null)
			return null;
		String[] parts = fileName.split("_");
		if (parts.length < 2 || !parts[0].startsWith(ODT2PACK_PREFIX))
			return null;
		String version = parts[0].substring(ODT2PACK_PREFIX.length());
		String[] segments = version.split("\\.");
		if (segments.length != 3)
			return null;
		for (String segment : segments) {
			if (segment.isEmpty())
				return null;
			for (int i = 0; i < segment.length(); i++) {
				if (!Character.isDigit(segment.charAt(i)))
					return null;
			}
		}
		return version;
	}

	private void packInFolder() {
		// ODT2Pack-{major.minor.patch}_{ClientValue}_{Description}.zip
		Enumeration<URL> urls = context.getBundle().findEntries("/META-INF", "ODT2Pack-*.zip", false);
		if (urls == null)
			return;
		
		setProcessInfo(getProcessInfo());
		
		List<File> filesToProcess = new ArrayList<>();
		
		while (urls.hasMoreElements()) {
			URL u = urls.nextElement();
			File toProcess = new File(u.getFile());
			if (odt2PackVersion(toProcess.getName()) == null) {
				ODTLog.warning(logger, ODTLog.MOD_2PACK, "Zip name is not ODT2Pack-n.n.n_Client_Description.zip, skip | "
						+ ODTLog.kvs("file", toProcess.getName()));
				continue;
			}
			currentFile = toProcess;
			
			if (installedPackage(null)) { // version is null, check file name only
				ODTLog.warning(logger, ODTLog.MOD_2PACK, "Zip already installed, skip | "
						+ ODTLog.kvs("file", currentFile.getName()));
			} else {
				filesToProcess.add(toProcess);
				file2urlMap.put(currentFile.getName(), u);
			}
			currentFile = null;
		}

		File[] fileArray = filesToProcess.toArray(new File[filesToProcess.size()]);
		Arrays.sort(fileArray, new Comparator<File>() {
			@Override
			public int compare(File f1, File f2) {
				int versionCompare = new Version(odt2PackVersion(f1.getName()))
						.compareTo(new Version(odt2PackVersion(f2.getName())));
				if (versionCompare != 0)
					return versionCompare;
				return f1.getName().compareTo(f2.getName());
			}
		});
		
		if (fileArray.length <= 0) {
			setSummary(Level.INFO, "No zip files to process in PackIn Folder from bundle:" + getName());
			return;
		}
		
		boolean cacheReset = false;
		MSession localSession = null;
		try {
			if (getDBLock()) {
				//Create Session to be able to create records in AD_ChangeLog
				if (Env.getContextAsInt(Env.getCtx(), Env.AD_SESSION_ID) <= 0) {
					localSession = MSession.get(Env.getCtx());
					if(localSession == null) {
						localSession = MSession.create(Env.getCtx());
					} else {
						localSession = new MSession(Env.getCtx(), localSession.getAD_Session_ID(), null);
					}
					localSession.setWebSession("ODT2PackActivator");
					localSession.saveEx();
				}
				for(File zipFile : fileArray) {
					currentFile = zipFile;
					if (!packIn(zipFile)) {
						// stop processing further packages if one fail
						String msg = "Failed application of " + zipFile;
						addLog(Level.WARNING, msg);
						if (getProcessInfo() != null) {
							getProcessInfo().setError(true);
							getProcessInfo().setSummary("@Error@: " + msg);
						}
						break;
					}
					addLog(Level.INFO, "Successful application of " + zipFile);
					filesToProcess.remove(zipFile);
					cacheReset = true;
				}
			} else {
				addLog(Level.WARNING, "Could not acquire the DB lock to automatically install the packins");
				return;
			}
		} catch (AdempiereSystemError e) {
			e.printStackTrace();
			addLog(Level.SEVERE, e.getLocalizedMessage());
		} finally {
			releaseLock();
			currentFile = null;
			if (localSession != null)
				localSession.logout();
		}
		ODTLog.info(logger, ODTLog.MOD_2PACK, "Cache reset after folder pack-in | "
				+ ODTLog.kvs("cacheReset", cacheReset));
		if (cacheReset)
			CacheMgt.get().reset();
		
		if (filesToProcess.size() > 0) {
			StringBuilder pending = new StringBuilder("The following packages were not applied: ");
			for (File file : filesToProcess) {
				pending.append("\n").append(file.getName());
			}
			addLog(Level.WARNING, pending.toString());
		}
		
	}
	
	private boolean packIn(File packinFile) {
		if (packinFile != null) {
			String fileName = packinFile.getName();
			ODTLog.info(logger, ODTLog.MOD_2PACK, "Installing zip pack-in | "
					+ ODTLog.kvs("file", fileName));

			// The convention for package names is: yyyymmddHHMM_ClientValue_InformationalDescription.zip
			String [] parts = fileName.split("_");
			String clientValue = parts[1];
			
			boolean allClients = clientValue.startsWith("ALL-CLIENTS");
			
			int[] clientIDs;
			if (allClients) {
				int[] seedClientIDs = new int[0];
				String seedClientValue = "";
				if (clientValue.startsWith("ALL-CLIENTS-")) {
					seedClientValue = clientValue.split("-")[2];
					seedClientIDs = getClientIDs(seedClientValue);				
					if (seedClientIDs.length == 0) {
						ODTLog.warning(logger, ODTLog.MOD_2PACK, "Seed client not found | "
								+ ODTLog.kvs("clientValue", seedClientValue));
						return false;
					}
				}
				int[] allClientIDs = new Query(Env.getCtx(), MClient.Table_Name, "AD_Client_ID>0 AND Value!=?", null)
						.setOnlyActiveRecords(true)
						.setParameters(seedClientValue)
						.setOrderBy("AD_Client_ID")
						.getIDs();
				// Process first the seed client, put seed in front of the array
				int shift = 0;
				if (seedClientIDs.length > 0)
					shift = 1;
				clientIDs = new int[allClientIDs.length + shift];
				if (seedClientIDs.length > 0)
					clientIDs[0] = seedClientIDs[0];
				for (int i = 0; i < allClientIDs.length; i++) {
					clientIDs[i+shift] = allClientIDs[i];
				}
			} else {
				clientIDs = getClientIDs(clientValue);
				if (clientIDs.length == 0) {
					ODTLog.warning(logger, ODTLog.MOD_2PACK, "Client not found | "
							+ ODTLog.kvs("clientValue", clientValue));
					return false;
				}
			}

			for (int clientID : clientIDs) {
				MClient client = MClient.get(Env.getCtx(), clientID);
				if  (allClients) {
					String message = "Installing " + fileName + " in client " + client.getValue() + "/" + client.getName();
					statusUpdate(message);
				}
				Env.setContext(Env.getCtx(), Env.AD_CLIENT_ID, client.getAD_Client_ID());
				
				FileOutputStream zipstream = null;
				try {
					// copy the resource to a temporary file to process it with 2pack
					URL packout = file2urlMap.get(fileName);
					InputStream stream = packout.openStream();
					File zipfile = File.createTempFile(fileName, "");
					zipstream = new FileOutputStream(zipfile);
				    byte[] buffer = new byte[1024];
				    int read;
				    while((read = stream.read(buffer)) != -1){
				    	zipstream.write(buffer, 0, read);
				    }
				    
					if (zipstream != null) {
						try {
							zipstream.close();
						} catch (Exception e2) {}
					}
				    
				    String tmpfileName = zipfile.getName();
					String fe = ""; //File extension
					String ff = ""; //File prefix
					int i = tmpfileName.lastIndexOf('.');
					if (i > 0) {
					    fe = tmpfileName.substring(i+1);
					    ff = tmpfileName.substring(0, i);
					}
					String targetFileName = ff + ".zip";
					
					Path targetDirPath = Paths.get(zipfile.getParent() + File.separator + fe);
					Path sourceFilePath = Paths.get(zipfile.getPath());
					Path targetFilePath = targetDirPath.resolve(targetFileName);

					// create the target directory, if directory exists, no effect
					Files.createDirectory(targetDirPath);
					Files.move(sourceFilePath, targetFilePath, StandardCopyOption.REPLACE_EXISTING);
					
					File targetFile = targetFilePath.toFile();
				    // call 2pack; null context records AD_Package_Imp.Name as the zip file name
					if (service != null) {
						service.merge(null, targetFile);
					} else {
						if (!directMerge(targetFile, null)) {
							return false;
						}
					}
				} catch (Throwable e) {
					ODTLog.severe(logger, ODTLog.MOD_2PACK, "Pack-in failed | "
							+ ODTLog.kvs("file", fileName, "clientId", clientID), e);
					return false;
				} finally {
					Env.setContext(Env.getCtx(), Env.AD_CLIENT_ID, 0);
				}
				
				ODTLog.info(logger, ODTLog.MOD_2PACK, "Zip pack-in completed for client | "
						+ ODTLog.kvs("file", packinFile.getPath(), "clientId", clientID));
			}
			if (allClients ) {
				// when arriving here it means an ALL-CLIENTS 2pack was processed successfully
				// register a record on System to avoid future reprocesses of the same file
				X_AD_Package_Imp_Proc pimpr = new X_AD_Package_Imp_Proc(Env.getCtx(), 0, null);
				pimpr.setName(fileName);
				pimpr.setDateProcessed(new Timestamp(System.currentTimeMillis()));
				pimpr.setP_Msg("This ALL-CLIENT 2Pack was applied successfully in all tenants");
				pimpr.setAD_Package_Source_Type(X_AD_Package_Imp_Proc.AD_PACKAGE_SOURCE_TYPE_File);
				pimpr.saveEx();
				X_AD_Package_Imp pimp = new X_AD_Package_Imp(Env.getCtx(), 0, null);
				pimp.setAD_Package_Imp_Proc_ID(pimpr.getAD_Package_Imp_Proc_ID());
				pimp.setName(fileName);
				pimp.setPK_Status("Completed successfully");
				pimp.setDescription("This ALL-CLIENT 2Pack was applied successfully in all tenants");
				pimp.setProcessed(true);
				pimp.saveEx();
			}
		}

		return true;
	}
	
	private int[] getClientIDs(String clientValue) {
		String where = "Value = ?";
		Query q = new Query(Env.getCtx(), MClient.Table_Name, where, null)
				.setParameters(clientValue)
				.setOnlyActiveRecords(true);
		return q.getIDs();
	}
	

	private static final String XMLTag_ODTPackage = "ODTPackage";
	private static final String XMLTag_ODTVersion = "ODTVersion";
	private static final String XMLTag_ODTPackageName = "Name";

	private void updatePackageEntityTypeFromXml(Properties ctx, Element eODTVersion, MKSODTPackage odtPackage)
	{
		if (odtPackage == null)
			return;

		String entityType = ODTXmlHelper.extractEntityTypeFromVersionXml(ctx, eODTVersion);
		if (entityType == null || entityType.isEmpty())
			return;

		odtPackage.setEntityType(entityType);
		odtPackage.saveEx();
		ODTLog.info(logger, ODTLog.MOD_2PACK, "Updated package EntityType | "
				+ ODTLog.kvs("package", odtPackage.getName(), "entityType", entityType));
	}

	private boolean odtInstall(URL configURL) {
		if (configURL == null)
			return true;

		InputStream input = null;
		try {
			input = configURL.openStream();

			ODTLog.info(logger, ODTLog.MOD_2PACK, "Loading ODT package from bundle | "
					+ ODTLog.kvs("bundle", getName()));
			DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
			dbf.setNamespaceAware(true);
			dbf.setIgnoringElementContentWhitespace(true);

			DocumentBuilder builder = dbf.newDocumentBuilder();
			Document doc = builder.parse(new InputSource(input));

			NodeList migrations = doc.getDocumentElement().getElementsByTagName(XMLTag_ODTPackage);
			for (int j = 0; j < migrations.getLength(); j++)
			{
				Properties ctx = Env.getCtx();

				Element eODTPackage = (Element) migrations.item(j);
				Element eODTVersion = (Element) eODTPackage.getElementsByTagName(XMLTag_ODTVersion).item(0);

				String packageName = ((Element) eODTPackage.getElementsByTagName(XMLTag_ODTPackageName).item(0))
						.getTextContent().trim();
				int versionNo = Integer.parseInt(eODTVersion.getAttribute(X_KS_ODTVersion.COLUMNNAME_VersionNo));

				if (ODTVersionCheck.evaluate(ctx, packageName, versionNo, logger, false) != ODTVersionCheck.Result.INSTALL)
					continue;

				ODTInstallEngine.installFromVersionXml(ctx, eODTVersion, null, logger);
				CacheMgt.get().reset();
				MKSODTPackage importedPackage = MKSODTPackage.importFromXmlNode(ctx, eODTPackage);
				updatePackageEntityTypeFromXml(ctx, eODTVersion, importedPackage);
			}

			ODTLog.info(logger, ODTLog.MOD_2PACK, "ODT package installed from bundle | "
					+ ODTLog.kvs("bundle", getName()));
			return true;
		} catch (Exception ex) {
			ODTLog.severe(logger, ODTLog.MOD_2PACK, "ODT install failed | "
					+ ODTLog.kvs("bundle", getName()), ex);
			return false;
		} finally {
			if (input != null) {
				try {
					input.close();
				} catch (IOException e) {
					// ignore
				}
			}
		}
	}


	protected void setupPackInContext() {
		super.setupPackInContext();
		Env.setContext(Env.getCtx(), Env.AD_USER_ID, SystemIDs.USER_SYSTEM);
		Env.setContext(Env.getCtx(), Env.AD_ROLE_ID, SystemIDs.ROLE_SYSTEM);
	};

	@Override
	protected void frameworkStarted() {
		if (service != null) {
			if (Adempiere.isStarted()) {
				Adempiere.getThreadPoolExecutor().execute(new Runnable() {			
					@Override
					public void run() {
						ClassLoader cl = Thread.currentThread().getContextClassLoader();
						try {
							Thread.currentThread().setContextClassLoader(ODT2PackActivator.class.getClassLoader());
							setupPackInContext();
							installPackage();
						} finally {
							ServerContext.dispose();
							service = null;
							Thread.currentThread().setContextClassLoader(cl);
						}
					}
				});
			} else {
				Adempiere.addServerStateChangeListener(new ServerStateChangeListener() {				
					@Override
					public void stateChange(ServerStateChangeEvent event) {
						if (event.getEventType() == ServerStateChangeEvent.SERVER_START && service != null) {
							ClassLoader cl = Thread.currentThread().getContextClassLoader();
							try {
								Thread.currentThread().setContextClassLoader(ODT2PackActivator.class.getClassLoader());
								setupPackInContext();
								installPackage();
							} finally {
								ServerContext.dispose();
								service = null;
								Thread.currentThread().setContextClassLoader(cl);
							}
						}					
					}
				});
			}
		}
	}
}
