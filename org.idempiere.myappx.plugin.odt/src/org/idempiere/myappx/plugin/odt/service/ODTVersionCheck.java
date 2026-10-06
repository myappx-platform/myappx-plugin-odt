/**********************************************************************
 * Copyright (C) Contributors                                          *
 * Contributors: ken.longnan@gmail.com                               *
 **********************************************************************/

package org.idempiere.myappx.plugin.odt.service;

import java.util.Properties;
import java.util.logging.Level;

import org.compiere.model.MTable;
import org.compiere.model.Query;
import org.compiere.util.CLogger;

import org.idempiere.myappx.plugin.odt.model.X_KS_ODTPackage;
import org.idempiere.myappx.plugin.odt.model.X_KS_ODTVersion;
import org.idempiere.myappx.plugin.odt.util.ODTLog;

/**
 * Bundle install version gate for ODT packages (by package Name + VersionNo).
 *
 * <p>On first install of the ODT plugin itself, {@code KS_ODTPackage} is not yet in AD
 * (it is created from {@code META-INF/ODTPackage.xml}). Version lookup is skipped until
 * the ODT runtime dictionary exists.
 */
public final class ODTVersionCheck
{
	public enum Result
	{
		/** New VersionNo is greater than installed; proceed with install. */
		INSTALL,
		/** New VersionNo equals installed; skip. */
		SKIP_ALREADY_INSTALLED,
		/** New VersionNo is lower than installed; skip (Activator aborts entire run). */
		SKIP_DOWNGRADE
	}

	private ODTVersionCheck()
	{
	}

	/**
	 * @return {@code true} when {@code KS_ODTPackage} is registered in AD (ODT self-install done).
	 */
	public static boolean isOdtRuntimeAvailable(Properties ctx)
	{
		return MTable.getTable_ID(X_KS_ODTPackage.Table_Name) > 0;
	}

	/** Highest installed {@link X_KS_ODTVersion#COLUMNNAME_VersionNo} for the package name, or 0 when none. */
	public static int resolveInstalledVersionNo(Properties ctx, String packageName)
	{
		if (packageName == null || packageName.isEmpty())
			return 0;

		if (!isOdtRuntimeAvailable(ctx))
			return 0;

		X_KS_ODTPackage odtPkg = new Query(ctx, X_KS_ODTPackage.Table_Name, "Name=?", null)
				.setParameters(packageName)
				.firstOnly();
		if (odtPkg == null)
			return 0;

		X_KS_ODTVersion odtVer = new Query(ctx, X_KS_ODTVersion.Table_Name, "KS_ODTPackage_ID=?", null)
				.setParameters(odtPkg.get_ID())
				.setOrderBy(X_KS_ODTVersion.COLUMNNAME_VersionNo + " DESC")
				.firstOnly();
		return odtVer != null ? odtVer.getVersionNo() : 0;
	}

	/**
	 * @param strictDowngrade when {@code true}, downgrade yields {@link Result#SKIP_DOWNGRADE};
	 *        when {@code false} (2Pack path), downgrade and equal both skip without distinguishing
	 */
	public static Result evaluate(Properties ctx, String packageName, int newVersionNo, CLogger log,
			boolean strictDowngrade)
	{
		if (!isOdtRuntimeAvailable(ctx))
		{
			ODTLog.info(log, ODTLog.MOD_VERSION, "Bootstrap self-install (KS_ODTPackage not in AD yet) | "
					+ ODTLog.kvs("package", packageName, "versionNo", newVersionNo));
			return Result.INSTALL;
		}

		int oldVersionNo = resolveInstalledVersionNo(ctx, packageName);

		if (newVersionNo > oldVersionNo)
		{
			ODTLog.info(log, ODTLog.MOD_VERSION, "Install new version | "
					+ ODTLog.kvs("package", packageName, "newVersionNo", newVersionNo, "oldVersionNo", oldVersionNo));
			if (oldVersionNo != 0)
				logUninstallTodo(log);
			return Result.INSTALL;
		}

		if (newVersionNo == oldVersionNo)
		{
			ODTLog.warning(log, ODTLog.MOD_VERSION, "Skip: version already installed | "
					+ ODTLog.kvs("package", packageName, "versionNo", newVersionNo));
			return Result.SKIP_ALREADY_INSTALLED;
		}

		// newVersionNo < oldVersionNo
		if (strictDowngrade)
		{
			ODTLog.warning(log, ODTLog.MOD_VERSION, "Skip: downgrade not allowed | "
					+ ODTLog.kvs("package", packageName, "newVersionNo", newVersionNo, "oldVersionNo", oldVersionNo));
			return Result.SKIP_DOWNGRADE;
		}

		ODTLog.info(log, ODTLog.MOD_VERSION, "Skip: version not newer | "
				+ ODTLog.kvs("package", packageName, "newVersionNo", newVersionNo, "oldVersionNo", oldVersionNo));
		return Result.SKIP_ALREADY_INSTALLED;
	}

	private static void logUninstallTodo(CLogger log)
	{
		ODTLog.info(log, ODTLog.MOD_VERSION, "Uninstall old version before upgrade | status=@TODO@");
	}
}
