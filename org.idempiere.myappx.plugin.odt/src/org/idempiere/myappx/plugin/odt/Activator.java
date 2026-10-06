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

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.Properties;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.adempiere.plugin.utils.AdempiereActivator;
import org.compiere.model.SystemIDs;
import org.compiere.util.CacheMgt;
import org.compiere.util.Env;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import org.idempiere.myappx.plugin.odt.model.MKSODTPackage;
import org.idempiere.myappx.plugin.odt.model.X_KS_ODTVersion;
import org.idempiere.myappx.plugin.odt.service.ODTInstallEngine;
import org.idempiere.myappx.plugin.odt.service.ODTVersionCheck;
import org.idempiere.myappx.plugin.odt.util.ODTLog;

public class Activator extends AdempiereActivator {

	private static final String XMLTag_ODTPackage = "ODTPackage";
	private static final String XMLTag_ODTVersion = "ODTVersion";
	private static final String XMLTag_ODTPackageName = "Name";

	@Override
	protected void packIn() {
		// Dont packIn 2Pack.zip
	}

	@Override
	protected void install() {
		URL configURL = getContext().getBundle().getEntry("META-INF/ODTPackage.xml");
		if (configURL == null)
			return;

		InputStream input = null;
		try {
			input = configURL.openStream();

			ODTLog.info(logger, ODTLog.MOD_ACTIVATOR, "Loading ODTPackage from bundle | "
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
				Env.setContext(ctx, Env.AD_CLIENT_ID, 0);
				Env.setContext(ctx, Env.AD_USER_ID, SystemIDs.USER_SYSTEM);
				Env.setContext(ctx, Env.AD_ROLE_ID, SystemIDs.ROLE_SYSTEM);

				Element eODTPackage = (Element) migrations.item(j);
				Element eODTVersion = (Element) eODTPackage.getElementsByTagName(XMLTag_ODTVersion).item(0);

				String packageName = ((Element) eODTPackage.getElementsByTagName(XMLTag_ODTPackageName).item(0))
						.getTextContent().trim();
				int versionNo = Integer.parseInt(eODTVersion.getAttribute(X_KS_ODTVersion.COLUMNNAME_VersionNo));

				// Version gate skipped when KS_ODTPackage not in AD yet (bootstrap self-install)
				ODTVersionCheck.Result versionCheck = ODTVersionCheck.evaluate(ctx, packageName, versionNo, logger, true);
				if (versionCheck != ODTVersionCheck.Result.INSTALL)
				{
					ODTLog.info(logger, ODTLog.MOD_ACTIVATOR, "Skip package install | "
							+ ODTLog.kvs("package", packageName, "versionNo", versionNo, "result", versionCheck));
					return;
				}

				ODTLog.info(logger, ODTLog.MOD_ACTIVATOR, "Install package | "
						+ ODTLog.kvs("package", packageName, "versionNo", versionNo));
				ODTInstallEngine.installFromVersionXml(ctx, eODTVersion, null, logger);
				CacheMgt.get().reset();
				MKSODTPackage.importFromXmlNode(ctx, eODTPackage);
				ODTLog.info(logger, ODTLog.MOD_ACTIVATOR, "Package installed and imported | "
						+ ODTLog.kvs("package", packageName, "versionNo", versionNo));
			}

			ODTLog.info(logger, ODTLog.MOD_ACTIVATOR, "Finished bundle install | "
					+ ODTLog.kvs("bundle", getName()));
		} catch (Exception ex) {
			ODTLog.severe(logger, ODTLog.MOD_ACTIVATOR, "Bundle install failed | "
					+ ODTLog.kvs("bundle", getName()), ex);
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
}
