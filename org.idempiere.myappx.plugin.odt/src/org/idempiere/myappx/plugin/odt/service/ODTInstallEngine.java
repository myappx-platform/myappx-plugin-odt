/**********************************************************************
 * Copyright (C) Contributors                                          *
 * Contributors: ken.longnan@gmail.com                               *
 **********************************************************************/

package org.idempiere.myappx.plugin.odt.service;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.logging.Level;

import org.adempiere.exceptions.AdempiereException;
import org.compiere.model.MColumn;
import org.compiere.model.MTable;
import org.compiere.model.PO;
import org.compiere.model.X_AD_Column;
import org.compiere.model.X_AD_TreeNodeMM;
import org.compiere.util.CLogger;
import org.compiere.util.DB;
import org.compiere.util.Util;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import org.idempiere.myappx.plugin.odt.model.AttachmentODTUtil;
import org.idempiere.myappx.plugin.odt.model.I_KS_ODTPackage;
import org.idempiere.myappx.plugin.odt.model.MKSODTObjectData;
import org.idempiere.myappx.plugin.odt.model.MKSODTObjectDataLine;
import org.idempiere.myappx.plugin.odt.model.MKSODTVersion;
import org.idempiere.myappx.plugin.odt.model.ODTDdlService;
import org.idempiere.myappx.plugin.odt.model.ODTIndexService;
import org.idempiere.myappx.plugin.odt.model.ODTPOBuilder;
import org.idempiere.myappx.plugin.odt.model.ODTPOHelper;
import org.idempiere.myappx.plugin.odt.model.ODTPostInstallService;
import org.idempiere.myappx.plugin.odt.model.ODTXmlHelper;
import org.idempiere.myappx.plugin.odt.model.X_KS_ODTObjectData;
import org.idempiere.myappx.plugin.odt.model.X_KS_ODTObjectDataLine;
import org.idempiere.myappx.plugin.odt.util.ODTLog;

/**
 * Unified ODT install pipeline: AD ObjectData → Attachments → post-install DDL.
 */
public final class ODTInstallEngine
{
	private static final String XML_TAG_ODTObjectData = "ODTObjectData";
	private static final String XML_TAG_ODTObjectDataLine = "ODTObjectDataLine";

	private ODTInstallEngine()
	{
	}

	/** Install from {@link MKSODTVersion} database records (Process: Package Install). */
	public static void installFromVersion(MKSODTVersion version)
	{
		if (version == null)
			throw new AdempiereException("ODT version is null");

		Properties ctx = version.getCtx();
		String trxName = version.get_TrxName();
		CLogger log = CLogger.getCLogger(MKSODTVersion.class);

		I_KS_ODTPackage odtPackage = version.getKS_ODTPackage();
		List<MKSODTObjectData> objectDatas = version.getODTObjectDatas();
		ODTLog.info(log, ODTLog.MOD_INSTALL, "Start install from version | "
				+ ODTLog.kvs("package", odtPackage.getName(), "objectType", odtPackage.getObjectType(),
						"versionId", version.get_ID(), "objectDataCount", objectDatas.size()));

		LinkedHashSet<Integer> pendingColumnIds = new LinkedHashSet<>();

		for (MKSODTObjectData odtod : objectDatas)
		{
			if (X_KS_ODTObjectData.OBJECTDATA_TYPE_Attachment.equals(odtod.getObjectData_Type()))
				continue;

			ODTLog.fine(log, ODTLog.MOD_INSTALL, "Apply ObjectData | "
					+ ODTLog.kvs("name", odtod.getName(), "uuid", odtod.getObjectData_UUID(),
							"tableId", odtod.getAD_Table_ID()));

			MTable table = MTable.get(ctx, odtod.getAD_Table_ID());
			PO po = ODTPOHelper.generatePO(odtod.getObjectData_UUID(), table, trxName);

			if (po.get_ID() != 0)
				odtod.setObjectData_Action(X_KS_ODTObjectData.OBJECTDATA_ACTION_Update);
			else
				odtod.setObjectData_Action(X_KS_ODTObjectData.OBJECTDATA_ACTION_Insert);
			odtod.saveEx();

			for (MKSODTObjectDataLine odtodl : odtod.getODTObjectDataLines())
			{
				po = applyObjectDataLine(po, table, ctx, trxName, log,
						odtodl.getAD_Column_ID(), odtodl.getNewID(), odtodl.getNewValue(),
						odtodl.isNewNullValue(), odtodl.getNewUUID(), odtod.getName());
			}

			po = finalizeObjectData(po, table, odtod.getObjectData_UUID(), ctx, trxName, log, pendingColumnIds);
			if (po.get_ID() != 0 && X_KS_ODTObjectData.OBJECTDATA_ACTION_Insert.equals(odtod.getObjectData_Action()))
			{
				odtod.setObjectData_Action(X_KS_ODTObjectData.OBJECTDATA_ACTION_Update);
				odtod.saveEx();
			}

			applySqlStatements(odtod.getSQL_Apply(), trxName, log, odtod.getName());
		}

		for (MKSODTObjectData odtod : objectDatas)
		{
			if (!X_KS_ODTObjectData.OBJECTDATA_TYPE_Attachment.equals(odtod.getObjectData_Type()))
				continue;
			ODTLog.fine(log, ODTLog.MOD_INSTALL, "Apply attachment | "
					+ ODTLog.kvs("name", odtod.getName(), "uuid", odtod.getObjectData_UUID()));
			AttachmentODTUtil.installProcessAttachment(ctx, odtod, trxName, log);
		}

		ODTPostInstallService.postInstallPackage(ctx,
				odtPackage.getEntityType(),
				pendingColumnIds,
				ODTIndexService.collectTableIndexUuidsFromVersion(ctx, version.get_ID(), trxName),
				log, trxName);

		ODTLog.info(log, ODTLog.MOD_INSTALL, "Finished install from version | "
				+ ODTLog.kvs("package", odtPackage.getName(), "versionId", version.get_ID(),
						"pendingColumns", pendingColumnIds.size()));
	}

	/**
	 * Install AD objects from an {@code ODTVersion} XML element (Bundle activators).
	 * Does not import into KS_ODT* tables.
	 */
	public static void installFromVersionXml(Properties ctx, Element eODTVersion, String trxName, CLogger log)
	{
		if (eODTVersion == null)
			throw new AdempiereException("ODTVersion XML element is null");

		LinkedHashSet<Integer> pendingColumnIds = new LinkedHashSet<>();
		NodeList childrenOD = eODTVersion.getElementsByTagName(XML_TAG_ODTObjectData);
		int objectDataCount = childrenOD.getLength();
		ODTLog.info(log, ODTLog.MOD_INSTALL, "Start install from version XML | "
				+ ODTLog.kvs("objectDataCount", objectDataCount,
						"entityType", ODTXmlHelper.extractEntityTypeFromVersionXml(ctx, eODTVersion)));

		for (int i = 0; i < childrenOD.getLength(); i++)
		{
			Element eODTOD = (Element) childrenOD.item(i);
			if (X_KS_ODTObjectData.OBJECTDATA_TYPE_Attachment.equals(
					eODTOD.getAttribute(X_KS_ODTObjectData.COLUMNNAME_ObjectData_Type)))
				continue;

			String objectDataUuid = eODTOD.getAttribute(X_KS_ODTObjectData.COLUMNNAME_ObjectData_UUID);
			MTable table = MTable.get(ctx, Integer.parseInt(
					eODTOD.getAttribute(X_KS_ODTObjectData.COLUMNNAME_AD_Table_ID)));
			ODTLog.fine(log, ODTLog.MOD_INSTALL, "Apply ObjectData from XML | "
					+ ODTLog.kvs("uuid", objectDataUuid, "table", table.getTableName()));
			PO po = ODTPOHelper.generatePO(objectDataUuid, table, trxName);

			NodeList childrenODL = eODTOD.getElementsByTagName(XML_TAG_ODTObjectDataLine);
			for (int ii = 0; ii < childrenODL.getLength(); ii++)
			{
				Element eODTODL = (Element) childrenODL.item(ii);
				po = applyObjectDataLineFromXml(po, table, ctx, trxName, log, eODTODL, objectDataUuid);
			}

			po = finalizeObjectData(po, table, objectDataUuid, ctx, trxName, log, pendingColumnIds);
			applySqlStatements(readSqlApplyFromXml(eODTOD), trxName, log, objectDataUuid);
		}

		for (int i = 0; i < childrenOD.getLength(); i++)
		{
			Element eODTOD = (Element) childrenOD.item(i);
			if (!X_KS_ODTObjectData.OBJECTDATA_TYPE_Attachment.equals(
					eODTOD.getAttribute(X_KS_ODTObjectData.COLUMNNAME_ObjectData_Type)))
				continue;
			ODTLog.fine(log, ODTLog.MOD_INSTALL, "Apply attachment from XML | "
					+ ODTLog.kvs("uuid", eODTOD.getAttribute(X_KS_ODTObjectData.COLUMNNAME_ObjectData_UUID)));
			AttachmentODTUtil.installProcessAttachmentFromXml(ctx, eODTOD, log);
		}

		ODTPostInstallService.postInstallPackage(ctx,
				ODTXmlHelper.extractEntityTypeFromVersionXml(ctx, eODTVersion),
				pendingColumnIds,
				ODTXmlHelper.collectTableIndexUuidsFromVersionXml(ctx, eODTVersion),
				log, trxName);

		ODTLog.info(log, ODTLog.MOD_INSTALL, "Finished install from version XML | "
				+ ODTLog.kvs("objectDataCount", objectDataCount, "pendingColumns", pendingColumnIds.size()));
	}

	private static PO applyObjectDataLineFromXml(PO po, MTable table, Properties ctx, String trxName,
			CLogger log, Element eODTODL, String objectDataName)
	{
		int adColumnId = Integer.parseInt(eODTODL.getAttribute(X_KS_ODTObjectDataLine.COLUMNNAME_AD_Column_ID));
		Integer newId = parseIntegerOrNull(xmlChildText(eODTODL, X_KS_ODTObjectDataLine.COLUMNNAME_NewID));
		String newValue = xmlChildText(eODTODL, X_KS_ODTObjectDataLine.COLUMNNAME_NewValue);
		Boolean isNewNullValue = parseBooleanOrNull(xmlChildText(eODTODL, X_KS_ODTObjectDataLine.COLUMNNAME_IsNewNullValue));
		String newUuid = xmlChildText(eODTODL, X_KS_ODTObjectDataLine.COLUMNNAME_NewUUID);

		return applyObjectDataLine(po, table, ctx, trxName, log,
				adColumnId, newId, newValue, isNewNullValue, newUuid, objectDataName);
	}

	private static PO applyObjectDataLine(PO po, MTable table, Properties ctx, String trxName, CLogger log,
			int adColumnId, Integer newId, String newValue, Boolean isNewNullValue, String newUuid,
			String objectDataName)
	{
		try
		{
			return ODTPOBuilder.buildPO(po, adColumnId, newId, newValue, isNewNullValue, newUuid, table, ctx, log);
		}
		catch (Exception ex)
		{
			String columnName = MColumn.getColumnName(ctx, adColumnId);
			throw new AdempiereException("Failed to build PO for ObjectData '" + objectDataName
					+ "', table=" + table.getTableName() + ", column=" + columnName, ex);
		}
	}

	private static PO finalizeObjectData(PO po, MTable table, String objectDataUuid, Properties ctx,
			String trxName, CLogger log, LinkedHashSet<Integer> pendingColumnIds)
	{
		Set<String> emptyStringColumns = ODTPOBuilder.takeEmptyStringColumns(po);

		if (po instanceof X_AD_TreeNodeMM && po.is_new())
		{
			X_AD_TreeNodeMM poMM = (X_AD_TreeNodeMM) po;
			String whereClause = "AD_Tree_ID=? AND Node_ID=?";
			X_AD_TreeNodeMM reloadPO = (X_AD_TreeNodeMM) table.getPO(whereClause,
					new Object[] { poMM.getAD_Tree_ID(), poMM.getNode_ID() }, trxName);
			reloadPO.setSeqNo(poMM.getSeqNo());
			reloadPO.setParent_ID(poMM.getParent_ID());
			reloadPO.set_ValueNoCheck(reloadPO.getUUIDColumnName(), objectDataUuid);
			po = reloadPO;
		}

		po = ODTPOHelper.reconcileTrlPOIfExistsByNaturalKey(po, table, ctx, trxName, log);

		try
		{
			po.saveEx();
			persistEmptyStrings(po, emptyStringColumns, log);
		}
		catch (Exception ex)
		{
			throw new AdempiereException("Can't save PO | AD_Table:" + table.getTableName()
					+ " | ObjectData_UUID:" + objectDataUuid, ex);
		}

		if (po instanceof X_AD_Column)
			ODTDdlService.queueColumnSync((MColumn) po, table, pendingColumnIds);

		return po;
	}

	/**
	 * PO binds {@code ""} as NULL. After a successful insert that used a space placeholder,
	 * PostgreSQL can store a real empty string. Oracle treats {@code ''} as NULL, so it is skipped.
	 */
	private static void persistEmptyStrings(PO po, Set<String> columns, CLogger log)
	{
		if (po == null || columns == null || columns.isEmpty())
			return;
		if (!DB.isPostgreSQL())
			return;

		String uuidCol = PO.getUUIDColumnName(po.get_TableName());
		String uuid = po.get_ValueAsString(uuidCol);
		if (Util.isEmpty(uuid, true))
		{
			ODTLog.info(log, ODTLog.MOD_INSTALL, "Skip empty-string persist (no UUID) | "
					+ ODTLog.kvs("table", po.get_TableName()));
			return;
		}

		StringBuilder sql = new StringBuilder("UPDATE ").append(po.get_TableName()).append(" SET ");
		List<Object> params = new ArrayList<>();
		boolean first = true;
		for (String columnName : columns)
		{
			if (Util.isEmpty(columnName, true))
				continue;
			if (!first)
				sql.append(", ");
			first = false;
			sql.append(DB.getDatabase().quoteColumnName(columnName)).append("=?");
			params.add("");
		}
		if (params.isEmpty())
			return;
		sql.append(" WHERE ").append(uuidCol).append("=?");
		params.add(uuid);

		int updated = DB.executeUpdateEx(sql.toString(), params.toArray(), po.get_TrxName());
		if (log != null && log.isLoggable(Level.FINE))
			ODTLog.fine(log, ODTLog.MOD_INSTALL, "Persisted empty strings | "
					+ ODTLog.kvs("table", po.get_TableName(), "columns", columns.size(), "updated", updated));
	}

	private static void applySqlStatements(String sqlApply, String trxName, CLogger log, String label)
	{
		if (Util.isEmpty(sqlApply))
			return;

		ODTLog.fine(log, ODTLog.MOD_INSTALL, "Apply SQL | " + ODTLog.kvs("label", label, "statementCount",
				sqlApply.split("--//--").length));

		String[] sqls = sqlApply.split("--//--");
		for (String sql : sqls)
		{
			if (Util.isEmpty(sql, true))
				continue;
			PreparedStatement pstmt = null;
			Statement stmt = null;
			try
			{
				pstmt = DB.prepareStatement(sql, trxName);
				stmt = pstmt.getConnection().createStatement();
				stmt.executeUpdate(sql);
			}
			catch (Exception e)
			{
				ODTLog.severe(log, ODTLog.MOD_INSTALL, "SQL_Apply failed | "
						+ ODTLog.kvs("label", label, "sql", sql), e);
			}
			finally
			{
				DB.close(stmt);
				DB.close(pstmt);
			}
		}
	}

	private static String readSqlApplyFromXml(Element eODTOD)
	{
		String sql = xmlChildText(eODTOD, X_KS_ODTObjectData.COLUMNNAME_SQL_Apply);
		return sql != null ? sql.trim() : null;
	}

	private static String xmlChildText(Element parent, String tagName)
	{
		NodeList nodes = parent.getElementsByTagName(tagName);
		if (nodes.getLength() == 0)
			return null;
		Node node = nodes.item(0);
		if (node == null)
			return null;
		String text = node.getTextContent();
		if (text == null || text.isEmpty())
			return null;
		return text;
	}

	private static Integer parseIntegerOrNull(String text)
	{
		if (text == null || text.trim().isEmpty())
			return null;
		return Integer.valueOf(text.trim());
	}

	private static Boolean parseBooleanOrNull(String text)
	{
		if (text == null || text.trim().isEmpty())
			return null;
		return Boolean.valueOf(text.trim());
	}
}
