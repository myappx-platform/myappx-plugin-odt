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

package org.idempiere.myappx.plugin.odt.model;

import java.io.File;
import java.io.FileWriter;
import java.sql.ResultSet;
import java.util.Properties;
import java.util.logging.Level;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.adempiere.exceptions.AdempiereException;
import org.compiere.model.MAttachment;
import org.compiere.model.MLot;
import org.compiere.model.Query;
import org.compiere.util.CLogger;
import org.compiere.util.Env;
import org.compiere.util.Trx;
import org.w3c.dom.Document;

import org.idempiere.myappx.plugin.odt.util.ODTLog;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

public class MKSODTPackage extends X_KS_ODTPackage {

	/**
	 *
	 */
	private static final long serialVersionUID = 5423620746522513712L;
	/**	Logger					*/
	private static CLogger		s_log = CLogger.getCLogger(MKSODTPackage.class);	

	public MKSODTPackage(Properties ctx, int KS_ODTPackage_ID, String trxName) {
		super(ctx, KS_ODTPackage_ID, trxName);
	}

	public MKSODTPackage(Properties ctx, ResultSet rs, String trxName) {
		super(ctx, rs, trxName);
	}

	@Override
	protected boolean afterSave(boolean newRecord, boolean success) {
		if (!isImportedODT() && newRecord)
		{
			X_KS_ODTVersion odtVersion = new X_KS_ODTVersion(getCtx(), 0, get_TrxName());
			odtVersion.setKS_ODTPackage_ID(get_ID());
			odtVersion.setIsActive(true);
			odtVersion.setName(getName() + "-V" + 1);
			odtVersion.setVersionNo(1);
			odtVersion.setVersion_Status(X_KS_ODTVersion.VERSION_STATUS_Draft);
			if (!odtVersion.save())
				return false;
		}
		return true;
	}

	public void exportPackage() {
		try {
			String fileName = "ODTPackage_" + getEntityType() + ".xml";
			File file = new File(System.getProperty("java.io.tmpdir"), fileName);
			if (file.exists())
				file.delete();

			Document document = null;
			DocumentBuilderFactory factory = DocumentBuilderFactory
					.newInstance();
			DocumentBuilder builder = factory.newDocumentBuilder();
			document = builder.newDocument();
			Element root = document.createElement("ODT_IDEMPIERE");
			Element ODTPackage = document.createElement("ODTPackage");
			ODTPackage.setAttribute("ID", String.valueOf(get_ID()));
			ODTPackage.setAttribute("UUID", get_Value(getUUIDColumnName()).toString());
			ODTPackage.setAttribute(COLUMNNAME_ObjectType, getObjectType());
			//ODTPackage.setAttribute(COLUMNNAME_EntityType, getEntityType());
			ODTPackage.setAttribute(COLUMNNAME_IsImportedODT, "Y");

			ODTXmlHelper.appendCDataChild(document, ODTPackage, COLUMNNAME_Name, getName());
			ODTXmlHelper.appendCDataChild(document, ODTPackage, COLUMNNAME_Description, getDescription());

			ODTPackage.appendChild(toXmlNode(document));
			root.appendChild(ODTPackage);
			document.appendChild(root);

			TransformerFactory transfac = TransformerFactory.newInstance();
			//transfac.setAttribute("indent-number", 2);

			Transformer trans = transfac.newTransformer();
			trans.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");
			trans.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no");
			trans.setOutputProperty(OutputKeys.INDENT, "yes");
			trans.setOutputProperty(OutputKeys.STANDALONE, "yes");

			FileWriter fw = new FileWriter(file);
			StreamResult result = new StreamResult(fw);
			DOMSource source = new DOMSource(document);
			trans.transform(source, result);
			fw.close();
			ODTLog.info(s_log, ODTLog.MOD_PACKAGE, "Export package XML | "
					+ ODTLog.kvs("path", file.getAbsolutePath(), "entityType", getEntityType()));
			
			// add XML Package as attachment
			MAttachment matt = createAttachment();
			matt.addEntry(file);
			matt.saveEx();			
			ODTLog.info(s_log, ODTLog.MOD_PACKAGE, "Saved package XML to attachment | "
					+ ODTLog.kvs("path", file.getAbsolutePath()));
			// clear the tempfile
			ODTLog.info(s_log, ODTLog.MOD_PACKAGE, "Delete temp export file | "
					+ ODTLog.kvs("path", file.getAbsolutePath(), "deleted", file.delete()));
		} catch (Exception e) {
			throw new AdempiereException(e);
		}
	}

	public MKSODTVersion getNewestVersion()
	{
		String whereClause = X_KS_ODTVersion.COLUMNNAME_VersionNo
				+ " = (select max(" + X_KS_ODTVersion.COLUMNNAME_VersionNo + ") from " + X_KS_ODTVersion.Table_Name
				+ " where " + X_KS_ODTVersion.COLUMNNAME_KS_ODTPackage_ID + " = ?)"
				+ " and " + X_KS_ODTVersion.COLUMNNAME_KS_ODTPackage_ID + " = ?";
		return new Query(getCtx(), X_KS_ODTVersion.Table_Name, whereClause, get_TrxName())
			.setParameters(get_ID(), get_ID())
			.firstOnly();
	}

	private Node toXmlNode(Document document)
	{
		MKSODTVersion odtversion = getNewestVersion();
		return odtversion.toXmlNode(document);
	}

	public static MKSODTPackage fromXmlNode(Element element, Properties ctx)
	{
		int id = Integer.valueOf(element.getAttribute("ID"));
		String uuid = element.getAttribute("UUID");
		//String EntityType = element.getAttribute(COLUMNNAME_EntityType);
		String ObjectType = element.getAttribute(COLUMNNAME_ObjectType);
		String IsImportedODT = element.getAttribute(COLUMNNAME_IsImportedODT);
		Node name = (Element)element.getElementsByTagName(COLUMNNAME_Name).item(0);
		Node description = (Element)element.getElementsByTagName(COLUMNNAME_Description).item(0);

		MKSODTPackage odtpackage = null;
		odtpackage = new MKSODTPackage(ctx, 0, null);
		//odtpackage.setEntityType(EntityType);
		odtpackage.setObjectType(ObjectType);
		odtpackage.setIsImportedODT("Y".equals(IsImportedODT));
		odtpackage.setName(name.getTextContent().trim());
		odtpackage.setDescription(description.getTextContent().trim());
		odtpackage.set_ValueNoCheck(odtpackage.getUUIDColumnName(), uuid);

		return odtpackage;
	}

	public static MKSODTPackage importFromXmlNode(Properties ctx, Element element)
	{
		if (!"ODTPackage".equals(element.getLocalName()))
			return null;

		String uuid = element.getAttribute("UUID");
		MKSODTPackage odtpackage = new Query(ctx, X_KS_ODTPackage.Table_Name,
				getUUIDColumnName(Table_Name) + "=?", null)
				.setParameters(uuid)
				.firstOnly();
		if (odtpackage == null) {
			odtpackage = MKSODTPackage.fromXmlNode(element, ctx);
		}
		odtpackage.saveEx();

		Element version = (Element)element.getElementsByTagName("ODTVersion").item(0);
		MKSODTVersion.importFromXmlNode(odtpackage, version);

		return odtpackage;
	}

	public void apply()
	{
		return;
	}
}
