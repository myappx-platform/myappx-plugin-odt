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

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.text.SimpleDateFormat;
import java.util.List;
import java.util.Properties;
import java.util.logging.Level;

import org.compiere.model.I_AD_IndexColumn;
import org.compiere.model.I_AD_InfoColumn;
import org.compiere.model.I_AD_InfoWindow;
import org.compiere.model.I_AD_Ref_Table;
import org.compiere.model.I_AD_TableIndex;
import org.compiere.model.I_AD_ToolBarButton;
import org.compiere.model.I_AD_WF_Node;
import org.compiere.model.I_AD_WF_NodeNext;
import org.compiere.model.I_AD_Process;
import org.compiere.model.I_AD_Window;
import org.compiere.model.I_AD_Workflow;
import org.compiere.model.MAttachment;
import org.compiere.model.Lookup;
import org.compiere.model.MColumn;
import org.compiere.model.M_Element;
import org.compiere.model.MLookup;
import org.compiere.model.MTable;
import org.compiere.model.Null;
import org.compiere.model.PO;
import org.compiere.model.POInfo;
import org.compiere.model.Query;
import org.compiere.model.X_AD_EntityType;
import org.adempiere.exceptions.AdempiereException;
import org.compiere.util.CLogger;
import org.compiere.util.DB;
import org.compiere.util.DisplayType;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import org.idempiere.myappx.plugin.odt.service.ODTInstallEngine;
import org.idempiere.myappx.plugin.odt.util.ODTLog;

public class MKSODTVersion extends X_KS_ODTVersion
{
	/**
	 *
	 */
	private static final long serialVersionUID = -3867829385607097983L;

	private static final String W_ENTITY_TYPE = "EntityType=? AND AD_Client_ID=0";

	/** _Trl rows for system-level parents of the given EntityType. */
	private static String trlWhere(String parentTable, String parentKeyColumn)
	{
		return parentKeyColumn + " IN (SELECT " + parentKeyColumn + " FROM " + parentTable
				+ " WHERE " + W_ENTITY_TYPE + ") AND AD_Client_ID=0";
	}

	/** Cached seq allocator for {@link #versionRefresh()} (reset per refresh). */
	private int nextAdObjectSeqNo = -1;
	private int nextAnySeqNo = -1;

	private CLogger log = CLogger.getCLogger(MKSODTVersion.class);

	public MKSODTVersion(Properties ctx, int KS_ODTVersion_ID, String trxName) {
		super(ctx, KS_ODTVersion_ID, trxName);
	}

	public MKSODTVersion(Properties ctx, ResultSet rs, String trxName) {
		super(ctx, rs, trxName);
	}

	public MKSODTVersion(MKSODTPackage parent, MKSODTVersion from) {
		this (parent.getCtx(), 0, parent.get_TrxName());
		copyValues(from, this);
		setClientOrg(parent);
		setKS_ODTPackage_ID(parent.getKS_ODTPackage_ID());
	}

	public List<MKSODTObjectData> getODTObjectDatas()
	{
		String whereClause = "KS_ODTVersion_ID=?";
		List<MKSODTObjectData> list = new Query(getCtx(), X_KS_ODTObjectData.Table_Name, whereClause, get_TrxName())
											.setParameters(get_ID())
											.setOrderBy(X_KS_ODTObjectData.COLUMNNAME_SeqNo)
											.list();
		return list;
	}



	public void packageInstall()
	{
		ODTInstallEngine.installFromVersion(this);
	}

	public void packageUninstal()
	{

	}

	public void linkEntityType(String entityType) 
	{
		I_KS_ODTPackage odtPackage = getKS_ODTPackage();
		ODTLog.info(log, ODTLog.MOD_EXPORT, "Link EntityType | "
				+ ODTLog.kvs("package", odtPackage.getName(), "entityType", entityType,
						"objectType", odtPackage.getObjectType()));

		int entityTypeTableId = MTable.get(getCtx(), X_AD_EntityType.Table_Name).getAD_Table_ID();
		M_Element entityTypeElement = M_Element.get(getCtx(), X_AD_EntityType.COLUMNNAME_EntityType, get_TrxName());
		if (entityTypeElement == null)
			throw new AdempiereException("AD_Element not found: " + X_AD_EntityType.COLUMNNAME_EntityType);

		String sqlDeleteOldODL = "DELETE FROM KS_ODTObjectDataLine WHERE KS_ODTObjectData_ID IN "
				+ "(SELECT KS_ODTObjectData_ID FROM KS_ODTObjectData WHERE KS_ODTVersion_ID=? AND AD_Table_ID=?)";
		DB.executeUpdateEx(sqlDeleteOldODL, new Object[] { get_ID(), entityTypeTableId }, get_TrxName());

		String sqlDeleteOldOD = "DELETE FROM KS_ODTObjectData WHERE KS_ODTVersion_ID=? AND AD_Table_ID=?";
		DB.executeUpdateEx(sqlDeleteOldOD, new Object[] { get_ID(), entityTypeTableId }, get_TrxName());

		exportObjectData(X_AD_EntityType.Table_Name, W_ENTITY_TYPE, new Object[] { entityType }, null);

		String sqlUpdateEntityType = "UPDATE KS_ODTObjectDataLine SET NewValue=? "
				+ "WHERE KS_ODTObjectData_ID IN ("
				+ "SELECT KS_ODTObjectData_ID FROM KS_ODTObjectData WHERE KS_ODTVersion_ID=? AND AD_Table_ID<>?"
				+ ") AND AD_Column_ID IN (SELECT AD_Column_ID FROM AD_Column WHERE AD_Element_ID=?)";
		DB.executeUpdateEx(sqlUpdateEntityType,
				new Object[] { entityType, get_ID(), entityTypeTableId, entityTypeElement.getAD_Element_ID() }, get_TrxName());
	}

	public void versionRefresh()
	{
		String sqlDeleteODL = "DELETE FROM KS_ODTObjectDataLine WHERE KS_ODTObjectData_ID IN ("
				+ "SELECT KS_ODTObjectData_ID FROM KS_ODTObjectData "
				+ "WHERE ObjectData_Type IN (?,?) AND KS_ODTVersion_ID=?)";
		DB.executeUpdateEx(sqlDeleteODL, new Object[] {
				X_KS_ODTObjectData.OBJECTDATA_TYPE_ADObject,
				X_KS_ODTObjectData.OBJECTDATA_TYPE_Attachment,
				get_ID() }, get_TrxName());

		String sqlDeleteOD = "DELETE FROM KS_ODTObjectData WHERE ObjectData_Type IN (?,?) AND KS_ODTVersion_ID=?";
		DB.executeUpdateEx(sqlDeleteOD, new Object[] {
				X_KS_ODTObjectData.OBJECTDATA_TYPE_ADObject,
				X_KS_ODTObjectData.OBJECTDATA_TYPE_Attachment,
				get_ID() }, get_TrxName());

		resetExportSeqCounters();

		I_KS_ODTPackage odtPackage = getKS_ODTPackage();
		String entityType = odtPackage.getEntityType();
		if (entityType == null || entityType.isEmpty())
			throw new AdempiereException("@FillMandatory@ @EntityType@ (@KS_ODTPackage_ID@)");
		Object[] etParam = new Object[] { entityType };

		ODTLog.info(log, ODTLog.MOD_EXPORT, "Version refresh started | "
				+ ODTLog.kvs("package", odtPackage.getName(), "entityType", entityType,
						"objectType", odtPackage.getObjectType(), "versionId", get_ID()));

		exportObjectData(X_AD_EntityType.Table_Name, W_ENTITY_TYPE, etParam, null);
		exportObjectData("AD_SysConfig", W_ENTITY_TYPE, etParam, null);
		exportObjectData("AD_Message", W_ENTITY_TYPE, etParam, null);
		exportObjectData("AD_Message_Trl", trlWhere("AD_Message", "AD_Message_ID"), etParam,
			"AD_Message_ID, AD_Language");
		exportObjectData("AD_Rule", W_ENTITY_TYPE, etParam, null);
		exportObjectData("AD_Window", W_ENTITY_TYPE, etParam, null);
		exportObjectData("AD_Window_Trl", trlWhere("AD_Window", "AD_Window_ID"), etParam,
			"AD_Window_ID, AD_Language");
		exportObjectData("AD_Form", W_ENTITY_TYPE, etParam, null);
		exportObjectData("AD_Form_Trl", trlWhere("AD_Form", "AD_Form_ID"), etParam,
			"AD_Form_ID, AD_Language");
		exportObjectData("AD_Element", W_ENTITY_TYPE, etParam, null);
		exportObjectData("AD_Element_Trl", trlWhere("AD_Element", "AD_Element_ID"), etParam,
			"AD_Element_ID, AD_Language");
		exportObjectData("AD_Val_Rule", W_ENTITY_TYPE, etParam, null);
		exportObjectData("AD_Reference", W_ENTITY_TYPE, etParam, null);
		exportObjectData("AD_Reference_Trl", trlWhere("AD_Reference", "AD_Reference_ID"), etParam,
			"AD_Reference_ID, AD_Language");

		exportObjectData("AD_Process", W_ENTITY_TYPE, etParam, null);
		exportProcessAttachments(entityType);
		exportObjectData("AD_Process_Trl", trlWhere("AD_Process", "AD_Process_ID"), etParam,
			"AD_Process_ID, AD_Language");
		exportObjectData("AD_Process_Para", W_ENTITY_TYPE, etParam, null);
		exportObjectData("AD_Process_Para_Trl", trlWhere("AD_Process_Para", "AD_Process_Para_ID"), etParam,
			"AD_Process_Para_ID, AD_Language");
		exportObjectData("AD_Table", W_ENTITY_TYPE, etParam, null);
		exportObjectData("AD_Table_Trl", trlWhere("AD_Table", "AD_Table_ID"), etParam,
			"AD_Table_ID, AD_Language");
		exportObjectData("AD_Column", W_ENTITY_TYPE, etParam, "AD_Table_ID, isKey desc");
		exportObjectData("AD_Column_Trl", trlWhere("AD_Column", "AD_Column_ID"), etParam,
			"AD_Column_ID, AD_Language");
		exportObjectData(I_AD_TableIndex.Table_Name, W_ENTITY_TYPE, etParam, null);
		exportObjectData(I_AD_IndexColumn.Table_Name, W_ENTITY_TYPE, etParam, "AD_TableIndex_ID, seqNO");
		exportObjectData(I_AD_Ref_Table.Table_Name, W_ENTITY_TYPE, etParam, null);
		exportObjectData("AD_Tab", W_ENTITY_TYPE, etParam, "AD_Window_ID, seqNo");
		exportObjectData("AD_Tab_Trl", trlWhere("AD_Tab", "AD_Tab_ID"), etParam,
			"AD_Tab_ID, AD_Language");
		exportObjectData("AD_FieldGroup", W_ENTITY_TYPE, etParam, null);
		exportObjectData("AD_FieldGroup_Trl", trlWhere("AD_FieldGroup", "AD_FieldGroup_ID"), etParam,
			"AD_FieldGroup_ID, AD_Language");
		exportObjectData("AD_Field", W_ENTITY_TYPE, etParam, "AD_Tab_ID, seqNo");
		exportObjectData("AD_Field_Trl", trlWhere("AD_Field", "AD_Field_ID"), etParam,
			"AD_Field_ID, AD_Language");
		exportObjectData(I_AD_InfoWindow.Table_Name, W_ENTITY_TYPE, etParam, null);
		exportObjectData(I_AD_InfoColumn.Table_Name, W_ENTITY_TYPE, etParam, "AD_InfoWindow_ID, seqNo");

		exportObjectData("AD_Ref_List", W_ENTITY_TYPE, etParam, null);
		exportObjectData("AD_Ref_List_Trl", trlWhere("AD_Ref_List", "AD_Ref_List_ID"), etParam,
			"AD_Ref_List_ID, AD_Language");

		exportObjectData(I_AD_ToolBarButton.Table_Name, W_ENTITY_TYPE, etParam, null);
		exportObjectData(I_AD_Workflow.Table_Name, W_ENTITY_TYPE, etParam, null);
		exportObjectData(I_AD_WF_Node.Table_Name, W_ENTITY_TYPE, etParam, null);
		exportObjectData(I_AD_WF_NodeNext.Table_Name, W_ENTITY_TYPE, etParam, null);
		exportObjectData("AD_Menu", W_ENTITY_TYPE, etParam, null);
		exportObjectData("AD_Menu_Trl", trlWhere("AD_Menu", "AD_Menu_ID"), etParam,
			"AD_Menu_ID, AD_Language");
		exportObjectData("AD_TreeNodeMM",
			"Node_ID IN (SELECT AD_Menu_ID FROM AD_Menu WHERE " + W_ENTITY_TYPE + ") AND AD_Client_ID=0",
			etParam, null);
	}

	private void exportProcessAttachments(String entityType)
	{
		if (log.isLoggable(Level.FINE))
			ODTLog.fine(log, ODTLog.MOD_EXPORT, "Export process attachments | "
					+ ODTLog.kvs("entityType", entityType));

		MTable processTable = MTable.get(getCtx(), I_AD_Process.Table_Name);
		PO[] processes = ODTQueryHelper.listPOs(processTable, W_ENTITY_TYPE, new Object[] { entityType },
				null, get_TrxName(), log);
		for (PO process : processes)
		{
			MAttachment attachment = process.getAttachment();
			if (attachment == null || attachment.getEntryCount() < 1)
				continue;

			String base64 = AttachmentODTUtil.exportZipAsBase64(attachment);
			if (base64 == null || base64.isEmpty())
				continue;

			String processUuid = requireObjectUuid(process, I_AD_Process.Table_Name);

			X_KS_ODTObjectData newod = new X_KS_ODTObjectData(getCtx(), 0, get_TrxName());
			newod.setKS_ODTVersion_ID(get_ID());
			newod.setName("AD_Attachment-" + I_AD_Process.Table_Name + "-" + processUuid);
			newod.setObjectData_Type(X_KS_ODTObjectData.OBJECTDATA_TYPE_Attachment);
			newod.setObjectData_Status(X_KS_ODTObjectData.OBJECTDATA_STATUS_Applied);
			newod.setObjectData_Action(X_KS_ODTObjectData.OBJECTDATA_ACTION_NA);
			newod.setAD_Table_ID(processTable.get_ID());
			newod.setRecord_ID(process.get_ID());
			newod.setSeqNo(allocateAnySeqNo());
			newod.setObjectData_UUID(processUuid);
			newod.setAttachmentPayload(base64);
			newod.saveEx();
		}
	}

	private void exportObjectData(String tableName, String whereClause, Object[] params, String orderClause)
	{
		if (log.isLoggable(Level.FINE))
			ODTLog.fine(log, ODTLog.MOD_EXPORT, "Export ObjectData | "
					+ ODTLog.kvs("table", tableName, "where", whereClause, "order", orderClause));

		MTable table = MTable.get(getCtx(), tableName);
		PO[] pos = ODTQueryHelper.listPOs(table, whereClause, params, orderClause, get_TrxName(), log);
		for (PO po : pos)
		{
			X_KS_ODTObjectData newod = new X_KS_ODTObjectData(getCtx(), 0, get_TrxName());
			newod.setKS_ODTVersion_ID(get_ID());
			newod.setName(tableName + "-" + po.get_ID());
			newod.setObjectData_Type(X_KS_ODTObjectData.OBJECTDATA_TYPE_ADObject);
			newod.setObjectData_Status(X_KS_ODTObjectData.OBJECTDATA_STATUS_Applied);
			newod.setObjectData_Action(X_KS_ODTObjectData.OBJECTDATA_ACTION_NA);
			newod.setAD_Table_ID(table.get_ID());
			newod.setRecord_ID(po.get_ID());
			newod.setSeqNo(allocateAdObjectSeqNo(tableName));
			newod.setObjectData_UUID(requireObjectUuid(po, tableName));
			newod.saveEx();
			exportObjectDataLine(po, newod.get_ID());
		}
	}

	/** ObjectData install is UUID-based; refresh must fail clearly when UUID is missing. */
	private String requireObjectUuid(PO po, String tableName)
	{
		if (po == null)
			throw new AdempiereException("Record not found for " + tableName);
		String uuidColumnName = po.getUUIDColumnName();
		if (uuidColumnName == null)
			throw new AdempiereException("Table " + tableName + " has no UUID column (Record_ID=" + po.get_ID() + ")");
		Object poValue = po.get_Value(uuidColumnName);
		if (poValue == null)
			throw new AdempiereException("UUID is null for " + tableName + " Record_ID=" + po.get_ID());
		return poValue.toString();
	}

	private void resetExportSeqCounters()
	{
		nextAdObjectSeqNo = -1;
		nextAnySeqNo = -1;
	}

	private int allocateAdObjectSeqNo(String tableName)
	{
		if (X_AD_EntityType.Table_Name.equalsIgnoreCase(tableName))
			return 10;
		if (nextAdObjectSeqNo < 0)
		{
			nextAdObjectSeqNo = DB.getSQLValue(get_TrxName(),
					"SELECT COALESCE(MAX(SeqNo),0) FROM KS_ODTObjectData WHERE KS_ODTVersion_ID=? AND ObjectData_Type=?",
					get_ID(), X_KS_ODTObjectData.OBJECTDATA_TYPE_ADObject);
		}
		nextAdObjectSeqNo += 10;
		return nextAdObjectSeqNo;
	}

	private int allocateAnySeqNo()
	{
		if (nextAnySeqNo < 0)
		{
			nextAnySeqNo = DB.getSQLValue(get_TrxName(),
					"SELECT COALESCE(MAX(SeqNo),0) FROM KS_ODTObjectData WHERE KS_ODTVersion_ID=?",
					get_ID());
		}
		nextAnySeqNo += 10;
		return nextAnySeqNo;
	}

	private void exportObjectDataLine(PO po, int newodID) {
		POInfo poInfo = POInfo.getPOInfo(po.getCtx(), po.get_Table_ID(), po.get_TrxName());
		int size = poInfo.getColumnCount();
		for (int j = 0; j < size; j++)
		{
			if (poInfo.isVirtualColumn(j))
				continue;

			Properties ctx = getCtx();
			//POInfoColumn infoCol = poInfo.getColumn(j);
			Object value = po.get_Value(j);
			String columnName = poInfo.getColumnName(j);
			Class<?> c = poInfo.getColumnClass(j);
			int columnDisplayType = poInfo.getColumnDisplayType(j);
			String refTableName = null;
			String uuid = null;

			if (log.isLoggable(Level.FINE))
				ODTLog.fine(log, ODTLog.MOD_EXPORT, "Export ObjectDataLine | "
						+ ODTLog.kvs("column", columnName, "value", value));

			boolean hasLookupValue = value != null && !Null.NULL.equals(value)
					&& !(value instanceof Integer && ((Integer) value).intValue() == 0);

			if (columnDisplayType == DisplayType.TableDir)
			{
				refTableName = columnName.substring(0, columnName.indexOf("_ID"));
			}
			else if (columnDisplayType == DisplayType.Table)
			{
				Lookup lookup = poInfo.getColumnLookup(j);
				MLookup mLookup = null;
				if (lookup != null) {
					mLookup = (MLookup)lookup;
				}
				int AD_Reference_Value_ID = 0;
				if (mLookup != null) {
					AD_Reference_Value_ID = mLookup.getAD_Reference_Value_ID();
				}
				int AD_Val_Rule_ID = 0;
				if (log.isLoggable(Level.FINE))
					ODTLog.fine(log, ODTLog.MOD_EXPORT, "Resolve Table lookup | "
							+ ODTLog.kvs("column", columnName, "AD_Reference_Value_ID", AD_Reference_Value_ID,
									"AD_Val_Rule_ID", AD_Val_Rule_ID));
				refTableName = ODTLookupHelper.findLookupTableName(AD_Reference_Value_ID, AD_Val_Rule_ID,
						columnName, ctx, log, hasLookupValue);
			}
			else if (columnDisplayType == DisplayType.Search)
			{
				MTable stable = MTable.get(ctx, po.get_Table_ID());
				MColumn scolumn = stable.getColumn(columnName);
				int AD_Reference_Value_ID = scolumn.getAD_Reference_Value_ID();
				int AD_Val_Rule_ID = scolumn.getAD_Val_Rule_ID();
				if (log.isLoggable(Level.FINE))
					ODTLog.fine(log, ODTLog.MOD_EXPORT, "Resolve Search lookup | "
							+ ODTLog.kvs("column", columnName, "AD_Reference_Value_ID", AD_Reference_Value_ID,
									"AD_Val_Rule_ID", AD_Val_Rule_ID));
				refTableName = ODTLookupHelper.findLookupTableName(AD_Reference_Value_ID, AD_Val_Rule_ID,
						columnName, ctx, log, hasLookupValue);
			}
			else if (columnDisplayType == DisplayType.ID)
			{
				// TODO:hardcode
				if ("AD_TreeNodeMM".equals(po.get_TableName())
					&& ("Node_ID".equals(columnName) || "Parent_ID".equals(columnName)))
				{
					refTableName = "AD_Menu";
				}
				else if (poInfo.isKey(j))
				{
					// skip, it is ok
				}
				else
					//	TODO: M_Product.C_SubscriptionType_ID
					if (log.isLoggable(Level.WARNING))
						ODTLog.warning(log, ODTLog.MOD_EXPORT, "Unsupported DisplayType ID | "
								+ ODTLog.kvs("table", po.get_TableName(), "column", columnName));
			}
			else if (columnDisplayType == DisplayType.PAttribute)
			{
				//	TODO: M_Product.M_AttributeSetInstance_ID
				if (log.isLoggable(Level.WARNING))
					ODTLog.warning(log, ODTLog.MOD_EXPORT, "Unsupported DisplayType PAttribute | "
							+ ODTLog.kvs("table", po.get_TableName(), "column", columnName));
			}
			else if (columnDisplayType == DisplayType.Locator)
			{
				refTableName = "M_Locator";
			}
			else if (columnDisplayType == DisplayType.Location)
			{
				refTableName = "C_Location";
			}
			else //	other DisplayType
			{
				// no refTableName
			}

			X_KS_ODTObjectDataLine newodl = new X_KS_ODTObjectDataLine(getCtx(), 0, get_TrxName());
			newodl.setKS_ODTObjectData_ID(newodID);
			newodl.setAD_Column_ID(poInfo.getAD_Column_ID(columnName));

			if (refTableName != null)
			{
				MTable refTable = MTable.get(ctx, refTableName);

				if (value != null && !value.equals(Null.NULL) && value instanceof Integer)
				{
					int record_id = ((Integer)value).intValue();
					if (record_id == 0 && "AD_User".equals(refTableName)) {
						// hardcode to SuperUser
						value = Integer.valueOf(100);
						record_id = 100;
					}
					if (record_id == -1 && "AD_Menu".equals(refTableName)) {
						// hardcode to Top Menu
						value = Integer.valueOf(0);
						record_id = 0;
					}

					if (log.isLoggable(Level.FINE))
						ODTLog.fine(log, ODTLog.MOD_EXPORT, "Resolve ref table ID | "
								+ ODTLog.kvs("refTable", refTableName, "recordId", record_id));

					if (record_id == 0
							&& !"AD_Client".equals(refTableName)
							&& !"AD_Org".equals(refTableName))
					{
						if (log.isLoggable(Level.FINE))
							ODTLog.fine(log, ODTLog.MOD_EXPORT, "Ref record ID is zero | "
									+ ODTLog.kvs("refTable", refTableName));
					}

					String idValue = ODTLookupHelper.findLookupIdValue(refTable, record_id, ctx, log);

					c = String.class;
					value = idValue;

					if ("AD_Client".equals(refTableName) && record_id == 0) {
						uuid = "11237b53-9592-4af1-b3c5-afd216514b5d"; // System Client
					} else if ("AD_Org".equals(refTableName) && record_id == 0) {
						uuid = "3ef41ffc-8ea9-454a-afa2-22949f402ff5"; // * Org
					} else if ("AD_Menu".equals(refTableName) && record_id == 0) {
						uuid = null; // Main Menu
					} else {
						PO refPO = refTable.getPO(record_id, get_TrxName());
						uuid = requireObjectUuid(refPO, refTableName);
					}

					newodl.setNewID(record_id);
					newodl.setNewUUID(uuid);
				}
				else
				{
					if (log.isLoggable(Level.FINE))
						ODTLog.fine(log, ODTLog.MOD_EXPORT, "Ref table value is null | "
								+ ODTLog.kvs("refTable", refTableName));
				}
			}

			if (value == null || Null.NULL.equals(value))
				newodl.setIsNewNullValue(true);
			else if (c == Object.class)
				newodl.setNewValue(value.toString());
			else if (value instanceof Integer || value instanceof BigDecimal)
				newodl.setNewValue(value.toString());
			else if (c == Boolean.class)
			{
				boolean bValue = false;
				if (value instanceof Boolean)
					bValue = ((Boolean)value).booleanValue();
				else
					bValue = "Y".equals(value);
				newodl.setNewValue(bValue ? "Y" : "N");
			}
			else if (value instanceof Timestamp)
			{
				Timestamp ts = (Timestamp)value;
				SimpleDateFormat sdf = new SimpleDateFormat(ODTConstants.DEFAULT_DATEFORMAT_PATTERN);
				newodl.setNewValue(sdf.format(ts));
			}
			else if (c == String.class)
				newodl.setNewValue((String)value);
//				else if (DisplayType.isLOB(dt))
//					col.appendChild(document.createCDATASection(value.toString()));
			else
				newodl.setNewValue(value.toString());

			newodl.saveEx();
		} // end of all columns
	}

	public Node toXmlNode(Document document)
	{
		Element elemntodtversion = document.createElement("ODTVersion");
		elemntodtversion.setAttribute("ID", String.valueOf(get_ID()));
		elemntodtversion.setAttribute("UUID", get_Value(getUUIDColumnName()).toString());
		elemntodtversion.setAttribute(COLUMNNAME_VersionNo, String.valueOf(getVersionNo()));
		elemntodtversion.setAttribute(COLUMNNAME_Version_Status, getVersion_Status());

		ODTXmlHelper.appendCDataChild(document, elemntodtversion, COLUMNNAME_Name,
				getName() == null ? "" : getName().trim());
		ODTXmlHelper.appendCDataChild(document, elemntodtversion, COLUMNNAME_Description,
				getDescription() == null ? "" : getDescription().trim());
		ODTXmlHelper.appendCDataChild(document, elemntodtversion, COLUMNNAME_SystemVersion, getSystemVersion());

		for (MKSODTObjectData odtod : getODTObjectDatas())
		{
			elemntodtversion.appendChild(odtod.toXmlNode(document));
		}

		return elemntodtversion;
	}

	public static MKSODTVersion fromXmlNode(Element element, int ODTPackage_ID, Properties ctx)
	{
		int id = Integer.valueOf(element.getAttribute("ID"));
		String uuid = element.getAttribute("UUID");
		String VersionNo = element.getAttribute(COLUMNNAME_VersionNo);
		String Version_Status = element.getAttribute(COLUMNNAME_Version_Status);
		Node name = (Element)element.getElementsByTagName(COLUMNNAME_Name).item(0);
		Node description = (Element)element.getElementsByTagName(COLUMNNAME_Description).item(0);
		Node SystemVersion = (Element)element.getElementsByTagName(COLUMNNAME_SystemVersion).item(0);

		MKSODTVersion odtversion = new Query(ctx, X_KS_ODTVersion.Table_Name,
				getUUIDColumnName(Table_Name) + "=?", null)
				.setParameters(uuid)
				.firstOnly();
		if (odtversion == null) {
			odtversion = new MKSODTVersion(ctx, 0, null);
		}

		odtversion.setKS_ODTPackage_ID(ODTPackage_ID);
		odtversion.setVersionNo(Integer.valueOf(VersionNo));
		odtversion.setVersion_Status(Version_Status);
		odtversion.setName(name.getTextContent().trim());
		odtversion.setDescription(description.getTextContent().trim());
		odtversion.setSystemVersion(SystemVersion.getTextContent());
		odtversion.set_ValueNoCheck(odtversion.getUUIDColumnName(), uuid);

		return odtversion;
	}

	public static void importFromXmlNode(MKSODTPackage odtpackage, Element element)
	{
		MKSODTVersion odtversion = MKSODTVersion.fromXmlNode(element, odtpackage.get_ID(), odtpackage.getCtx());
		odtversion.saveEx();

		NodeList children = element.getElementsByTagName("ODTObjectData");
		for (int i = 0; i < children.getLength(); i++)
		{
			Element elemntOD = (Element)children.item(i);
			MKSODTObjectData.importFromXmlNode(odtversion, elemntOD);
		}
	}
 }
