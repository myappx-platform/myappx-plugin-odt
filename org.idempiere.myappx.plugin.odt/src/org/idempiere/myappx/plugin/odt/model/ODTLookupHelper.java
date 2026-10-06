/**********************************************************************
 * Copyright (C) Contributors                                          *
 * Contributors: ken.longnan@gmail.com                               *
 **********************************************************************/

package org.idempiere.myappx.plugin.odt.model;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Properties;
import java.util.logging.Level;

import org.compiere.model.MColumn;
import org.compiere.model.MTable;
import org.compiere.model.Null;
import org.compiere.model.PO;
import org.compiere.model.POInfo;
import org.compiere.model.X_AD_Ref_Table;
import org.compiere.model.X_AD_Reference;
import org.compiere.util.CLogger;
import org.compiere.util.DB;
import org.compiere.util.DisplayType;

import org.idempiere.myappx.plugin.odt.util.ODTLog;

/** Lookup table resolution and identifier value formatting for ODT. */
public final class ODTLookupHelper
{
	private ODTLookupHelper()
	{
	}

	/**
	 * Resolve the lookup target table for Table / Search columns.
	 * Dynamic validation ({@code AD_Val_Rule}) is a UI filter, not a table resolver.
	 * <p>
	 * Order: Table Reference → TableDir name from {@code *_ID} when that table exists
	 * → val-rule exceptions for columns whose names do not match the target table
	 * (e.g. {@code Bill_BPartner_ID} + rule 230 → {@code C_BPartner}).
	 * Unresolved lookups log WARNING only when {@code logIfUnresolved} is true
	 * (caller should pass true when the column actually has a value).
	 */
	public static String findLookupTableName(int AD_Reference_Value_ID, int AD_Val_Rule_ID,
			String columnName, Properties ctx, CLogger log)
	{
		return findLookupTableName(AD_Reference_Value_ID, AD_Val_Rule_ID, columnName, ctx, log, true);
	}

	public static String findLookupTableName(int AD_Reference_Value_ID, int AD_Val_Rule_ID,
			String columnName, Properties ctx, CLogger log, boolean logIfUnresolved)
	{
		String refTableName = null;

		if (AD_Reference_Value_ID != 0)
		{
			X_AD_Reference adRef = new X_AD_Reference(ctx, AD_Reference_Value_ID, null);
			if (X_AD_Reference.VALIDATIONTYPE_TableValidation.equals(adRef.getValidationType()))
			{
				X_AD_Ref_Table tableRef = new X_AD_Ref_Table(ctx, AD_Reference_Value_ID, null);
				MTable refTable = MTable.get(ctx, tableRef.getAD_Table_ID());
				refTableName = refTable.getTableName();
			}
			else
			{
				ODTLog.severe(log, ODTLog.MOD_LOOKUP, "Unsupported AD_Reference.ValidationType | "
						+ ODTLog.kvs("AD_Reference_Value_ID", AD_Reference_Value_ID,
								"validationType", adRef.getValidationType(), "column", columnName));
			}
			return refTableName;
		}

		String tableDirName = tableDirNameFromColumn(columnName);
		if (isKnownTable(ctx, tableDirName))
			return tableDirName;

		refTableName = tableNameFromValRule(AD_Val_Rule_ID);
		if (refTableName == null && logIfUnresolved)
			ODTLog.warning(log, ODTLog.MOD_LOOKUP, "Unresolved lookup table | "
					+ ODTLog.kvs("column", columnName, "AD_Val_Rule_ID", AD_Val_Rule_ID,
							"AD_Reference_Value_ID", AD_Reference_Value_ID));
		return refTableName;
	}

	/** TableDir convention: {@code AD_CtxHelp_ID} → {@code AD_CtxHelp}. */
	private static String tableDirNameFromColumn(String columnName)
	{
		if (columnName == null)
			return null;
		int idx = columnName.indexOf("_ID");
		if (idx <= 0)
			return null;
		return columnName.substring(0, idx);
	}

	private static boolean isKnownTable(Properties ctx, String tableName)
	{
		if (tableName == null || tableName.isEmpty())
			return false;
		MTable table = MTable.get(ctx, tableName);
		return table != null && table.getAD_Table_ID() > 0;
	}

	/** Fallback when the column name does not match the target table. */
	private static String tableNameFromValRule(int AD_Val_Rule_ID)
	{
		switch (AD_Val_Rule_ID)
		{
			case 230: // C_BPartner (Trx) — e.g. Bill_BPartner_ID
				return "C_BPartner";
			case 231: // M_Product (Trx)
				return "M_Product";
			case 184:
				return "M_Lot";
			case 272:
			case 218:
				return "C_Order";
			case 220:
				return "C_Invoice";
			case 158:
				return "AD_Role";
			default:
				return null;
		}
	}

	/**
	 * ParentIdCol1#@#ParentIdCol2#@#100000#@#IdCol1#@#IdCol2
	 */
	public static String findLookupIdValue(MTable refTable, int record_id, Properties ctx, CLogger log)
	{
		String resultIdValue = null;

		PO refPO = refTable.getPO(record_id, null);
		String refTableName = refTable.getTableName();
		POInfo poInfo = POInfo.getPOInfo(ctx, refTable.getAD_Table_ID(), refPO.get_TrxName());

		MColumn[] idcols = getIdentifierColumnsByTable(refTable.getAD_Table_ID(), ctx, log);
		for (int i = 0; i < idcols.length; i++)
		{
			MColumn idcol = idcols[i];

			if (log != null && log.isLoggable(Level.FINE))
				ODTLog.fine(log, ODTLog.MOD_LOOKUP, "Resolve identifier column | "
						+ ODTLog.kvs("refTable", refTableName, "seqNo", idcol.getSeqNo(),
								"column", idcol.getColumnName(), "isIdentifier", idcol.isIdentifier(),
								"isParent", idcol.isParent(), "displayType", idcol.getAD_Reference_ID()));

			if (idcol.isParent())
				continue;

			if (!isValidIdentifierColumn(refTableName, idcol))
				continue;

			Object value = refPO.get_ValueOfColumn(idcol.getAD_Column_ID());
			Class<?> c = poInfo.getColumnClass(poInfo.getColumnIndex(idcol.getAD_Column_ID()));

			if (refTableName.equals("AD_Org") && record_id == 0)
				value = "*";

			if (refTableName.equals("AD_User") && record_id == 0)
				value = "System";

			if (refTableName.equals("AD_Role") && record_id == 0)
				value = "System Administrator";

			if (value == null || value.equals(Null.NULL))
			{
				if (log != null && log.isLoggable(Level.FINE))
					ODTLog.fine(log, ODTLog.MOD_LOOKUP, "Identifier value is null | "
							+ ODTLog.kvs("refTable", refTableName, "column", idcol.getColumnName()));
				resultIdValue = (resultIdValue == null ? "" : resultIdValue + ODTConstants.ID_VALUE_SEPARATOR + "");
			}
			else if (c == Object.class)
				resultIdValue = (resultIdValue == null ? value.toString() : resultIdValue + ODTConstants.ID_VALUE_SEPARATOR + value.toString());
			else if (value instanceof Integer || value instanceof BigDecimal)
				resultIdValue = (resultIdValue == null ? value.toString() : resultIdValue + ODTConstants.ID_VALUE_SEPARATOR + value.toString());
			else if (c == Boolean.class)
				resultIdValue = (resultIdValue == null ? value.toString() : resultIdValue + ODTConstants.ID_VALUE_SEPARATOR + value.toString());
			else if (value instanceof Timestamp)
			{
				Timestamp ts = (Timestamp) value;
				SimpleDateFormat sdf = new SimpleDateFormat(ODTConstants.DEFAULT_DATEFORMAT_PATTERN);
				resultIdValue = (resultIdValue == null ? sdf.format(ts) : resultIdValue + ODTConstants.ID_VALUE_SEPARATOR + sdf.format(ts));
			}
			else if (c == String.class)
				resultIdValue = (resultIdValue == null ? (String) value : resultIdValue + ODTConstants.ID_VALUE_SEPARATOR + (String) value);
			else
				resultIdValue = (resultIdValue == null ? value.toString() : resultIdValue + ODTConstants.ID_VALUE_SEPARATOR + value.toString());
		}

		MColumn[] pcols = getParentColumnsByTable(refTable.getAD_Table_ID(), ctx, log);
		for (int i = 0; i < pcols.length; i++)
		{
			MColumn pcol = pcols[i];
			if (log != null && log.isLoggable(Level.FINE))
				ODTLog.fine(log, ODTLog.MOD_LOOKUP, "Resolve parent column | "
						+ ODTLog.kvs("refTable", refTableName, "seqNo", pcol.getSeqNo(), "column", pcol.getColumnName()));

			String parentRefTableName = findLookupRefTableName(refTableName, pcol, ctx, log);
			MTable parentRefTable = MTable.get(ctx, parentRefTableName);
			Object value = refPO.get_ValueOfColumn(pcol.getAD_Column_ID());
			int precord_id = Integer.valueOf(value.toString());
			String parentIdValue = findLookupIdValue(parentRefTable, precord_id, ctx, log);
			resultIdValue = parentIdValue + ODTConstants.ID_VALUE_SEPARATOR + resultIdValue;
		}
		return resultIdValue;
	}

	public static boolean isValidIdentifierColumn(String tableName, MColumn idcol)
	{
		if ((tableName.equals("C_Invoice") && !idcol.getColumnName().equals("DocumentNo"))
			|| (tableName.equals("M_InOut") && !idcol.getColumnName().equals("DocumentNo"))
			|| (tableName.equals("C_Order") && !idcol.getColumnName().equals("DocumentNo"))
			|| (tableName.equals("C_Payment") && !idcol.getColumnName().equals("DocumentNo"))
			|| (tableName.equals("M_Requisition") && !idcol.getColumnName().equals("DocumentNo")))
			return false;

		return true;
	}

	public static String findLookupRefTableName(String tableName, MColumn col, Properties ctx, CLogger log)
	{
		String refTableName = null;
		String colName = col.getColumnName();

		if (col.getAD_Reference_ID() == DisplayType.Search)
		{
			int AD_Reference_Value_ID = col.getAD_Reference_Value_ID();
			int AD_Val_Rule_ID = col.getAD_Val_Rule_ID();
			if (log != null && log.isLoggable(Level.FINE))
				ODTLog.fine(log, ODTLog.MOD_LOOKUP, "Resolve lookup ref table | "
						+ ODTLog.kvs("column", colName, "AD_Reference_Value_ID", AD_Reference_Value_ID,
								"AD_Val_Rule_ID", AD_Val_Rule_ID));
			refTableName = findLookupTableName(AD_Reference_Value_ID, AD_Val_Rule_ID, colName, ctx, log);
		}
		else if (col.getAD_Reference_ID() == DisplayType.TableDir)
		{
			refTableName = colName.substring(0, colName.indexOf("_ID"));
		}
		else if (col.getAD_Reference_ID() == DisplayType.ID)
		{
			if ("AD_TreeNodeMM".equals(tableName) && "Node_ID".equals(colName))
			{
				refTableName = "AD_Menu";
			}
		}
		return refTableName;
	}

	public static MColumn[] getParentColumnsByTable(int AD_Table_ID, Properties ctx, CLogger log)
	{
		String sql = "SELECT * FROM AD_Column WHERE AD_Table_ID=? and isParent = 'Y' order by seqNo, ColumnName";
		ArrayList<MColumn> list = new ArrayList<>();
		PreparedStatement pstmt = null;
		ResultSet rs = null;
		try
		{
			pstmt = DB.prepareStatement(sql, null);
			pstmt.setInt(1, AD_Table_ID);
			rs = pstmt.executeQuery();
			while (rs.next())
				list.add(new MColumn(ctx, rs, null));
		}
		catch (Exception e)
		{
			ODTLog.severe(log, ODTLog.MOD_LOOKUP, "Query parent columns failed | "
					+ ODTLog.kvs("AD_Table_ID", AD_Table_ID), e);
		}
		finally
		{
			DB.close(rs, pstmt);
		}
		return list.toArray(new MColumn[list.size()]);
	}

	public static MColumn[] getIdentifierColumnsByTable(int AD_Table_ID, Properties ctx, CLogger log)
	{
		String sql = "SELECT * FROM AD_Column WHERE AD_Table_ID=? and isIdentifier = 'Y' order by seqNo";
		ArrayList<MColumn> list = new ArrayList<>();
		PreparedStatement pstmt = null;
		ResultSet rs = null;
		try
		{
			pstmt = DB.prepareStatement(sql, null);
			pstmt.setInt(1, AD_Table_ID);
			rs = pstmt.executeQuery();
			while (rs.next())
				list.add(new MColumn(ctx, rs, null));
		}
		catch (Exception e)
		{
			ODTLog.severe(log, ODTLog.MOD_LOOKUP, "Query identifier columns failed | "
					+ ODTLog.kvs("AD_Table_ID", AD_Table_ID), e);
		}
		finally
		{
			DB.close(rs, pstmt);
		}
		return list.toArray(new MColumn[list.size()]);
	}
}
