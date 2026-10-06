/**********************************************************************
 * Copyright (C) Contributors                                          *
 * Contributors: ken.longnan@gmail.com                               *
 **********************************************************************/

package org.idempiere.myappx.plugin.odt.model;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;

import org.adempiere.util.ProcessUtil;
import org.compiere.model.MPInstance;
import org.compiere.process.ProcessInfo;
import org.compiere.util.CLogger;
import org.compiere.util.Msg;

import org.idempiere.myappx.plugin.odt.util.ODTLog;

/** Post-install orchestration: column DDL, indexes, FKs, sequence check, role access. */
public final class ODTPostInstallService
{
	private ODTPostInstallService()
	{
	}

	public static void postInstallPackage(Properties ctx)
	{
		postInstallPackage(ctx, null, null, null, null, null);
	}

	/**
	 * Post-install without column sync (indexes / sequence / role access only).
	 */
	public static void postInstallPackage(Properties ctx, String entityType, List<String> tableIndexUuids,
			CLogger log, String trxName)
	{
		postInstallPackage(ctx, entityType, null, tableIndexUuids, log, trxName);
	}

	/**
	 * Post-install: column DDL, table indexes, foreign keys, sequence check, role access.
	 *
	 * @param columnIds AD_Column_ID values to synchronize (order preserved via {@link LinkedHashSet})
	 * @param tableIndexUuids AD_TableIndex UUIDs to validate in the database
	 */
	public static void postInstallPackage(Properties ctx, String entityType, LinkedHashSet<Integer> columnIds,
			List<String> tableIndexUuids, CLogger log, String trxName)
	{
		int columnCount = columnIds != null ? columnIds.size() : 0;
		int indexCount = tableIndexUuids != null ? tableIndexUuids.size() : 0;
		ODTLog.info(log, ODTLog.MOD_POST_INSTALL, "Start post-install | "
				+ ODTLog.kvs("entityType", entityType, "columnCount", columnCount, "indexCount", indexCount));

		ODTDdlService.synchronizeColumns(ctx, columnIds, log, trxName);
		ODTIndexService.validateTableIndexes(ctx, entityType, tableIndexUuids, log, trxName);
		ODTDdlService.deferForeignKeys(ctx, columnIds, log, trxName);
		doSequencCheck(ctx);
		doRoleAccessUpdate(ctx);

		ODTLog.info(log, ODTLog.MOD_POST_INSTALL, "Finished post-install | "
				+ ODTLog.kvs("entityType", entityType));
	}

	public static ProcessInfo doSequencCheck(Properties ctx)
	{
		ProcessInfo pi = new ProcessInfo("Sequence Check", 258);
		pi.setAD_Client_ID(0);
		pi.setAD_User_ID(100);
		pi.setClassName("org.compiere.process.SequenceCheck");
		MPInstance instance = new MPInstance(ctx, pi.getAD_Process_ID(), pi.getRecord_ID());
		if (!instance.save())
		{
			pi.setSummary(Msg.getMsg(ctx, "ProcessNoInstance"));
			pi.setError(true);
		}
		pi.setAD_PInstance_ID(instance.getAD_PInstance_ID());
		ProcessUtil.startJavaProcess(ctx, pi, null);
		return pi;
	}

	public static ProcessInfo doRoleAccessUpdate(Properties ctx)
	{
		ProcessInfo pi = new ProcessInfo("Role Access Update", 295);
		pi.setAD_Client_ID(0);
		pi.setAD_User_ID(100);
		pi.setClassName("org.compiere.process.RoleAccessUpdate");
		MPInstance instance = new MPInstance(ctx, pi.getAD_Process_ID(), pi.getRecord_ID());
		if (!instance.save())
		{
			pi.setSummary(Msg.getMsg(ctx, "ProcessNoInstance"));
			pi.setError(true);
		}
		pi.setAD_PInstance_ID(instance.getAD_PInstance_ID());
		ProcessUtil.startJavaProcess(ctx, pi, null);
		return pi;
	}
}
