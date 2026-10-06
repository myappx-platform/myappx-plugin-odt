/**********************************************************************
 * Copyright (C) Contributors                                          *
 * Contributors: ken.longnan@gmail.com                               *
 **********************************************************************/

package org.idempiere.myappx.plugin.odt.model;

import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Properties;
import java.util.logging.Level;

import org.compiere.model.MTable;
import org.compiere.model.Null;
import org.compiere.model.PO;
import org.compiere.model.POInfo;
import org.compiere.util.CLogger;
import org.compiere.util.Env;

import org.idempiere.myappx.plugin.odt.util.ODTLog;

/** PO lookup, creation, and translation-table reconciliation for ODT install. */
public final class ODTPOHelper
{
	private ODTPOHelper()
	{
	}

	public static PO findPO(String uuid, MTable table)
	{
		return findPO(uuid, table, null);
	}

	public static PO findPO(String uuid, MTable table, String trxName)
	{
		if (uuid == null || table == null)
			return null;
		String uuidColumn = PO.getUUIDColumnName(table.getTableName());
		return table.getPO(uuidColumn + "=?", new Object[] { uuid }, trxName);
	}

	public static PO generatePO(String uuid, MTable table)
	{
		return generatePO(uuid, table, null);
	}

	public static PO generatePO(String uuid, MTable table, String trxName)
	{
		PO po = findPO(uuid, table, trxName);
		if (po == null)
		{
			po = table.getPO(0, trxName);
			po.set_ValueNoCheck(po.getUUIDColumnName(), uuid);
		}
		return po;
	}

	public static PO reconcileTrlPOIfExistsByNaturalKey(PO po, MTable table, Properties ctx, String trxName, CLogger log)
	{
		String tableName = table.getTableName();
		if (!isTranslationTable(tableName))
			return po;

		String lang = (String) po.get_Value("AD_Language");
		if (lang != null)
			lang = lang.trim();
		if (lang == null || lang.isEmpty())
			return po;

		String parentCol = getTrlParentFkColumnName(tableName);
		if (parentCol == null)
			return po;

		Object pid = po.get_Value(parentCol);
		if (pid == null || isZeroIntegerLike(pid))
			return po;

		PO existing = findTrlByNaturalKey(table, parentCol, pid, lang, po, trxName);
		if (existing == null)
			return po;

		if (log != null && log.isLoggable(Level.INFO))
			ODTLog.info(log, ODTLog.MOD_PO, "Merge Trl onto existing row (natural key) | "
					+ ODTLog.kvs("table", tableName, "parentCol", parentCol, "parentId", pid, "language", lang));
		mergeStagedTrlIntoExisting(po, existing, ctx, trxName);
		return existing;
	}

	private static boolean isTranslationTable(String tableName)
	{
		return tableName != null && tableName.toUpperCase(Locale.ROOT).endsWith("_TRL");
	}

	private static boolean isZeroIntegerLike(Object pk)
	{
		Integer id = toSqlIntParentId(pk);
		return id == null || id.intValue() == 0;
	}

	private static Integer toSqlIntParentId(Object pk)
	{
		if (pk == null || Null.NULL.equals(pk))
			return null;
		if (pk instanceof Integer)
			return (Integer) pk;
		if (pk instanceof Long)
			return ((Long) pk).intValue();
		if (pk instanceof BigDecimal)
			return ((BigDecimal) pk).intValue();
		if (pk instanceof String)
		{
			String s = ((String) pk).trim();
			if (s.isEmpty())
				return null;
			try
			{
				return Integer.parseInt(s);
			}
			catch (NumberFormatException e)
			{
				return null;
			}
		}
		return null;
	}

	private static String getTrlParentFkColumnName(String tableName)
	{
		if (!isTranslationTable(tableName) || tableName.length() < 5)
		 return null;
		return tableName.substring(0, tableName.length() - 4) + "_ID";
	}

	private static void mergeStagedTrlIntoExisting(PO staged, PO existing, Properties ctx, String trxName)
	{
		POInfo info = POInfo.getPOInfo(ctx, staged.get_Table_ID(), trxName);
		for (int i = 0; i < info.getColumnCount(); i++)
		{
			if (info.isVirtualColumn(i))
				continue;
			String col = info.getColumnName(i);
			if ("AD_Client_ID".equalsIgnoreCase(col) || "AD_Org_ID".equalsIgnoreCase(col))
				continue;
			Object v = staged.get_Value(col);
			if (v == null || Null.NULL.equals(v))
				continue;
			existing.set_ValueNoCheck(col, v);
		}
	}

	private static PO findTrlByNaturalKey(MTable table, String parentCol, Object pid, String lang, PO staged, String trxName)
	{
		Integer parentId = toSqlIntParentId(pid);
		if (parentId == null || parentId.intValue() == 0)
			return null;

		LinkedHashSet<Integer> clients = new LinkedHashSet<>();
		clients.add(0);
		Object vc = staged.get_Value("AD_Client_ID");
		if (vc instanceof Integer && ((Integer) vc).intValue() != 0)
			clients.add((Integer) vc);
		int envClient = Env.getAD_Client_ID(staged.getCtx());
		if (envClient != 0)
			clients.add(envClient);
		int poClient = staged.getAD_Client_ID();
		if (poClient != 0)
			clients.add(poClient);

		String wc3 = parentCol + "=? AND AD_Language=? AND AD_Client_ID=?";
		for (Integer cid : clients)
		{
			if (cid == null)
				continue;
			PO row = table.getPO(wc3, new Object[] { parentId, lang, cid }, trxName);
			if (row != null)
				return row;
		}
		String wc2 = parentCol + "=? AND AD_Language=? ORDER BY AD_Client_ID";
		return table.getPO(wc2, new Object[] { parentId, lang }, trxName);
	}
}
