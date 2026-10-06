/**********************************************************************
 * Copyright (C) Contributors                                          *
 * Contributors: ken.longnan@gmail.com                               *
 **********************************************************************/

package org.idempiere.myappx.plugin.odt.model;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.logging.Level;

import org.compiere.model.I_AD_User;
import org.compiere.model.I_C_BPartner;
import org.compiere.model.Lookup;
import org.compiere.model.MColumn;
import org.compiere.model.MLookup;
import org.compiere.model.MTable;
import org.compiere.model.PO;
import org.compiere.model.POInfo;
import org.compiere.util.CLogger;
import org.compiere.util.DisplayType;

import org.idempiere.myappx.plugin.odt.util.ODTLog;

/** Applies ODT object data lines onto a {@link PO}. */
public final class ODTPOBuilder
{
	/**
	 * PO INSERT binds empty string as NULL ({@code params.add(null)}). A single space
	 * is used only as an insert placeholder; PostgreSQL then writes {@code ''} after save.
	 */
	private static final String MANDATORY_EMPTY_PLACEHOLDER = " ";

	private static final IdentityHashMap<PO, LinkedHashSet<String>> EMPTY_STRING_COLUMNS = new IdentityHashMap<>();

	private ODTPOBuilder()
	{
	}

	/** Columns that should become {@code ''} after a successful {@code saveEx}. */
	public static Set<String> takeEmptyStringColumns(PO po)
	{
		if (po == null)
			return Collections.emptySet();
		synchronized (EMPTY_STRING_COLUMNS)
		{
			LinkedHashSet<String> cols = EMPTY_STRING_COLUMNS.remove(po);
			return cols != null ? cols : Collections.emptySet();
		}
	}

	private static void rememberEmptyStringColumn(PO po, String columnName)
	{
		synchronized (EMPTY_STRING_COLUMNS)
		{
			EMPTY_STRING_COLUMNS.computeIfAbsent(po, k -> new LinkedHashSet<>()).add(columnName);
		}
	}

	private static boolean isTranslationTable(String tableName)
	{
		return tableName != null && tableName.toUpperCase(Locale.ROOT).endsWith("_TRL");
	}

	public static PO buildPO(PO po, Integer AD_Column_ID, Integer NewID, String NewValue, Boolean IsNewNullValue,
			String NewUUID, MTable table, Properties ctx, CLogger log)
	{
		String tableName = table.getTableName();
		final boolean trlTable = isTranslationTable(tableName);
		String columnName = MColumn.getColumnName(ctx, AD_Column_ID);
		int columnIndex = po.get_ColumnIndex(columnName);

		MColumn column = table.getColumn(columnName);
		POInfo poInfo = POInfo.getPOInfo(ctx, table.getAD_Table_ID(), po.get_TrxName());
		boolean isColumnUpdateable = poInfo.isColumnUpdateable(columnIndex);

		if (log != null && log.isLoggable(Level.FINE))
			ODTLog.fine(log, ODTLog.MOD_PO, "Apply ObjectDataLine | "
					+ ODTLog.kvs("table", tableName, "column", columnName));

		if ("AD_Chart_ID".equalsIgnoreCase(columnName))
			return po;

		if (("AD_Client_ID".equalsIgnoreCase(columnName) && !trlTable)
				|| "Created".equalsIgnoreCase(columnName)
				|| "Updated".equalsIgnoreCase(columnName)
				|| "UpdatedBy".equalsIgnoreCase(columnName))
			return po;

		if (poInfo.isKey(columnIndex) && !"AD_EntityType".equalsIgnoreCase(columnName)
				&& !trlTable)
			return po;

		if (I_C_BPartner.Table_Name.equalsIgnoreCase(tableName)
				&& ("TOTALOPENBALANCE".equalsIgnoreCase(columnName)
					|| "ACTUALLIFETIMEVALUE".equalsIgnoreCase(columnName)
					|| "FIRSTSALE".equalsIgnoreCase(columnName)
					|| "SO_CREDITUSED".equalsIgnoreCase(columnName)))
			return po;

		if (I_AD_User.Table_Name.equalsIgnoreCase(tableName)
				&& ("EMAILVERIFY".equalsIgnoreCase(columnName)
					|| "EMAILVERIFYDATE".equalsIgnoreCase(columnName)
					|| "LASTCONTACT".equalsIgnoreCase(columnName)
					|| "LASTRESULT".equalsIgnoreCase(columnName)))
			return po;

		if ("AD_Org_ID".equalsIgnoreCase(columnName)
				&& !"AD_Org".equalsIgnoreCase(po.get_TableName()))
		{
			po.setAD_Org_ID(NewID);
		}

		String refTableName = null;
		String columnValue = NewValue;
		int displayType = poInfo.getColumnDisplayType(columnIndex);
		boolean hasValidUUID = NewUUID != null && !NewUUID.isEmpty() && !"null".equalsIgnoreCase(NewUUID.trim());
		boolean hasLookupValue = hasValidUUID
				|| (!Boolean.TRUE.equals(IsNewNullValue) && columnValue != null && !columnValue.isEmpty());
		if (displayType == DisplayType.TableDir)
		{
			refTableName = columnName.substring(0, columnName.indexOf("_ID"));
		}
		else if (displayType == DisplayType.Locator)
		{
			refTableName = "M_Locator";
		}
		else if (displayType == DisplayType.Location)
		{
			refTableName = "C_Location";
		}
		else if (displayType == DisplayType.Table)
		{
			Lookup lookup = poInfo.getColumnLookup(columnIndex);
			MLookup mLookup = null;
			if (lookup != null)
				mLookup = (MLookup) lookup;
			int AD_Reference_Value_ID = 0;
			if (mLookup != null)
				AD_Reference_Value_ID = mLookup.getAD_Reference_Value_ID();
			int AD_Val_Rule_ID = 0;
			refTableName = ODTLookupHelper.findLookupTableName(AD_Reference_Value_ID,
					AD_Val_Rule_ID, columnName, ctx, log, hasLookupValue);
		}
		else if (displayType == DisplayType.Integer)
		{
			if (!IsNewNullValue && isColumnUpdateable && !"".equals(columnValue))
				po.set_ValueNoCheck(columnName, Integer.valueOf(columnValue));
			else if (!isColumnUpdateable)
				ODTLog.info(log, ODTLog.MOD_PO, "Skip non-updateable column | "
						+ ODTLog.kvs("table", tableName, "column", columnName, "displayType", "Integer"));
		}
		else if (displayType == DisplayType.Number
				|| displayType == DisplayType.Amount
				|| displayType == DisplayType.CostPrice
				|| displayType == DisplayType.Quantity)
		{
			if (!IsNewNullValue && isColumnUpdateable && !"".equals(columnValue))
				po.set_ValueNoCheck(columnName, new BigDecimal(columnValue));
			else if (!isColumnUpdateable)
				ODTLog.info(log, ODTLog.MOD_PO, "Skip non-updateable column | "
						+ ODTLog.kvs("table", tableName, "column", columnName, "displayType", "Number"));

			if ("PriceActual".equalsIgnoreCase(columnName) && !"".equals(columnValue)
					&& (tableName.equals("C_InvoiceLine") || tableName.equals("C_OrderLine")))
				po.set_ValueNoCheck(columnName, new BigDecimal(columnValue));
		}
		else if (displayType == DisplayType.DateTime
				|| displayType == DisplayType.Date
				|| displayType == DisplayType.Time)
		{
			SimpleDateFormat sdf = new SimpleDateFormat(ODTConstants.DEFAULT_DATEFORMAT_PATTERN);
			if (!IsNewNullValue && isColumnUpdateable && !"".equals(columnValue))
			{
				try
				{
					po.set_ValueNoCheck(columnName, new Timestamp(sdf.parse(columnValue).getTime()));
				}
				catch (ParseException e)
				{
					ODTLog.severe(log, ODTLog.MOD_PO, "Date parse failed | "
							+ ODTLog.kvs("table", tableName, "column", columnName, "value", columnValue), e);
				}
			}
			else if (!isColumnUpdateable)
				ODTLog.info(log, ODTLog.MOD_PO, "Skip non-updateable column | "
						+ ODTLog.kvs("table", tableName, "column", columnName, "displayType", "DateTime"));
		}
		else if (displayType == DisplayType.PAttribute)
		{
			po.set_ValueNoCheck(columnName, 0);
		}
		else if (displayType == DisplayType.Search)
		{
			int AD_Reference_Value_ID = column.getAD_Reference_Value_ID();
			int AD_Val_Rule_ID = column.getAD_Val_Rule_ID();
			refTableName = ODTLookupHelper.findLookupTableName(AD_Reference_Value_ID, AD_Val_Rule_ID,
					columnName, ctx, log, hasLookupValue);
		}
		else if (displayType == DisplayType.ID)
		{
			if ("AD_TreeNodeMM".equals(tableName)
					&& ("Node_ID".equals(columnName) || "Parent_ID".equals(columnName)))
				refTableName = "AD_Menu";
		}
		else
		{
			if (!IsNewNullValue && columnValue != null && !"".equals(columnValue))
			{
				if (isColumnUpdateable)
					po.set_ValueNoCheck(columnName, columnValue);
				else if (columnName.equals("DocumentNo")
					 || columnName.equals("MovementType")
					 || columnName.equals("IsSOTrx")
					 || columnName.equals("IsTransferred")
					 || (columnName.equals("TreeType") && tableName.equals("AD_Tree"))
					 || (columnName.equals("EntityType") && tableName.equals("AD_EntityType"))
					 || (trlTable && "AD_Language".equalsIgnoreCase(columnName)))
					po.set_ValueNoCheck(columnName, columnValue);
				else
					ODTLog.info(log, ODTLog.MOD_PO, "Skip non-updateable column | "
							+ ODTLog.kvs("table", tableName, "column", columnName));
			}
			else if (shouldWriteEmptyString(po, poInfo, columnIndex, displayType, IsNewNullValue, columnValue))
			{
				// INSERT of mandatory String columns (e.g. AD_SysConfig.Value) cannot leave NULL.
				// ODT XML <NewValue/> with IsNewNullValue=false means empty, not skip.
				// PO.buildInsertSQL binds "" as NULL, so a single space is required to satisfy NOT NULL.
				po.set_ValueNoCheck(columnName, MANDATORY_EMPTY_PLACEHOLDER);
				rememberEmptyStringColumn(po, columnName);
				if (log != null && log.isLoggable(Level.FINE))
					ODTLog.fine(log, ODTLog.MOD_PO, "Apply blank placeholder for mandatory column | "
							+ ODTLog.kvs("table", tableName, "column", columnName));
			}
		}

		if (refTableName != null)
		{
			if (hasValidUUID)
			{
				MTable reftable = MTable.get(ctx, refTableName);
				String uuidCol = PO.getUUIDColumnName(refTableName);
				PO refPO = reftable.getPO(uuidCol + "=?", new Object[] { NewUUID }, po.get_TrxName());
				if (refPO != null)
					po.set_ValueNoCheck(columnName, refPO.get_ID());
				else
					ODTLog.info(log, ODTLog.MOD_PO, "RefPO not found | "
							+ ODTLog.kvs("refTable", refTableName, "uuid", NewUUID, "column", columnName));
			}
			else if (isColumnUpdateable)
				po.set_ValueNoCheck(columnName, null);
		}

		return po;
	}

	/**
	 * Write a blank placeholder on insert when ODT says "not null" but the value is empty.
	 * Empty {@code <NewValue/>} is parsed as {@code null}; skipping it leaves DB NULL
	 * and fails NOT NULL columns such as {@code AD_SysConfig.Value}.
	 * Existing rows are left unchanged so updates do not wipe optional text.
	 */
	private static boolean shouldWriteEmptyString(PO po, POInfo poInfo, int columnIndex, int displayType,
			Boolean isNewNullValue, String columnValue)
	{
		if (!Boolean.FALSE.equals(isNewNullValue))
			return false;
		if (columnValue != null && !columnValue.isEmpty())
			return false;
		if (po == null || !po.is_new())
			return false;
		if (!poInfo.isColumnMandatory(columnIndex))
			return false;
		return DisplayType.isText(displayType);
	}
}
