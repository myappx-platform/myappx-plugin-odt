/**********************************************************************
 * Copyright (C) Contributors                                          *
 * Contributors: ken.longnan@gmail.com                               *
 **********************************************************************/

package org.idempiere.myappx.plugin.odt.model;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.logging.Level;

import org.adempiere.exceptions.AdempiereException;
import org.compiere.model.I_AD_TableIndex;
import org.compiere.model.MTable;
import org.compiere.model.MTableIndex;
import org.compiere.model.PO;
import org.compiere.model.Query;
import org.compiere.process.TableIndexValidate;
import org.compiere.util.CLogger;
import org.compiere.util.Trx;

import org.idempiere.myappx.plugin.odt.util.ODTLog;

/** Table index validation for ODT post-install. */
public final class ODTIndexService
{
	private ODTIndexService()
	{
	}

	/**
	 * Create or update physical DB indexes/constraints from {@link I_AD_TableIndex} metadata
	 * (same as 2Pack {@code TableIndexElementHandler} / {@link TableIndexValidate}).
	 */
	public static void validateTableIndexes(Properties ctx, String entityType, List<String> tableIndexUuids,
			CLogger log, String trxName)
	{
		List<MTableIndex> indexes = resolveTableIndexesToValidate(ctx, entityType, tableIndexUuids, trxName);
		if (indexes.isEmpty())
			return;

		ODTLog.info(log, ODTLog.MOD_INDEX, "Validate table indexes | "
				+ ODTLog.kvs("entityType", entityType, "count", indexes.size()));

		Trx trx = ODTDdlService.openDdlTrx(trxName, log);
		String effectiveTrxName = trx.getTrxName();

		for (MTableIndex index : indexes)
		{
			try
			{
				String result = TableIndexValidate.validateTableIndex(ctx, index, effectiveTrxName, null);
				ODTLog.info(log, ODTLog.MOD_INDEX, "TableIndexValidate | "
						+ ODTLog.kvs("index", index.getName(), "result", result));
			}
			catch (Exception e)
			{
				ODTLog.severeOrDefault(log, ODTLog.MOD_INDEX, "TableIndexValidate failed | "
						+ ODTLog.kvs("index", index.getName()), e);
				throw new AdempiereException("TableIndexValidate failed for " + index.getName(), e);
			}
		}

		ODTDdlService.finishDdlTrx(trx, trxName);
	}

	static List<MTableIndex> resolveTableIndexesToValidate(Properties ctx, String entityType,
			List<String> tableIndexUuids, String trxName)
	{
		LinkedHashSet<Integer> ids = new LinkedHashSet<>();

		if (tableIndexUuids != null && !tableIndexUuids.isEmpty())
		{
			MTable tableIndexTable = MTable.get(ctx, I_AD_TableIndex.Table_Name);
			String uuidColumn = PO.getUUIDColumnName(I_AD_TableIndex.Table_Name);
			for (String uuid : tableIndexUuids)
			{
				if (uuid == null || uuid.trim().isEmpty())
					continue;
				MTableIndex index = (MTableIndex) tableIndexTable.getPO(uuidColumn + "=?", new Object[] { uuid.trim() }, trxName);
				if (index != null && index.get_ID() > 0)
					ids.add(index.getAD_TableIndex_ID());
			}
		}
		else if (entityType != null && !entityType.isEmpty())
		{
			List<MTableIndex> list = new Query(ctx, MTableIndex.Table_Name, "EntityType=? AND IsActive='Y'", trxName)
					.setParameters(entityType)
					.list();
			for (MTableIndex index : list)
				ids.add(index.getAD_TableIndex_ID());
		}

		List<MTableIndex> indexes = new ArrayList<>();
		for (Integer id : ids)
			indexes.add(new MTableIndex(ctx, id, trxName));
		return indexes;
	}

	/** Collect {@link I_AD_TableIndex} UUIDs exported in an ODT version (database). */
	public static List<String> collectTableIndexUuidsFromVersion(Properties ctx, int KS_ODTVersion_ID, String trxName)
	{
		List<String> uuids = new ArrayList<>();
		int tableIndexTableId = MTable.get(ctx, I_AD_TableIndex.Table_Name).getAD_Table_ID();
		List<X_KS_ODTObjectData> rows = new Query(ctx, X_KS_ODTObjectData.Table_Name,
				"KS_ODTVersion_ID=? AND AD_Table_ID=?", trxName)
				.setParameters(KS_ODTVersion_ID, tableIndexTableId)
				.list();
		for (X_KS_ODTObjectData row : rows)
		{
			if (row.getObjectData_UUID() != null && !row.getObjectData_UUID().isEmpty())
				uuids.add(row.getObjectData_UUID());
		}
		return uuids;
	}
}
