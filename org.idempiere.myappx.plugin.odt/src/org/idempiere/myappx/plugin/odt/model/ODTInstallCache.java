/**********************************************************************
 * Copyright (C) Contributors                                          *
 * Contributors: ken.longnan@gmail.com                               *
 **********************************************************************/

package org.idempiere.myappx.plugin.odt.model;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import org.compiere.model.I_AD_Form;
import org.compiere.model.I_AD_InfoWindow;
import org.compiere.model.I_AD_Process;
import org.compiere.model.I_AD_Table;
import org.compiere.model.I_AD_Window;
import org.compiere.model.I_AD_Workflow;

/**
 * UUID to ID cache for one package install on the current thread.
 * Also records whether this install created a table, window, process, or form.
 */
public final class ODTInstallCache
{
	private static final ThreadLocal<ODTInstallCache> CURRENT = new ThreadLocal<>();

	private final Map<String, Integer> uuidToId = new HashMap<>();
	private boolean accessMaintenance;

	private ODTInstallCache()
	{
	}

	public static void begin()
	{
		CURRENT.set(new ODTInstallCache());
	}

	public static void end()
	{
		CURRENT.remove();
	}

	public static Integer id(String tableName, String uuid)
	{
		ODTInstallCache cache = CURRENT.get();
		if (cache == null || tableName == null || uuid == null || uuid.isEmpty())
			return null;
		return cache.uuidToId.get(key(tableName, uuid));
	}

	public static void remember(String tableName, String uuid, int id)
	{
		ODTInstallCache cache = CURRENT.get();
		if (cache == null || tableName == null || uuid == null || uuid.isEmpty() || id <= 0)
			return;
		cache.uuidToId.put(key(tableName, uuid), Integer.valueOf(id));
	}

	/**
	 * New dictionary records that Role Access Update or Sequence Check must see.
	 * Role access covers window, process, form, workflow, and info window. Sequence check covers new tables.
	 */
	public static void noteInserted(String tableName)
	{
		ODTInstallCache cache = CURRENT.get();
		if (cache == null || tableName == null)
			return;
		if (I_AD_Table.Table_Name.equalsIgnoreCase(tableName)
				|| I_AD_Window.Table_Name.equalsIgnoreCase(tableName)
				|| I_AD_Process.Table_Name.equalsIgnoreCase(tableName)
				|| I_AD_Form.Table_Name.equalsIgnoreCase(tableName)
				|| I_AD_Workflow.Table_Name.equalsIgnoreCase(tableName)
				|| I_AD_InfoWindow.Table_Name.equalsIgnoreCase(tableName))
			cache.accessMaintenance = true;
	}

	public static boolean needsAccessMaintenance()
	{
		ODTInstallCache cache = CURRENT.get();
		return cache != null && cache.accessMaintenance;
	}

	private static String key(String tableName, String uuid)
	{
		return tableName.toUpperCase(Locale.ROOT) + "\n" + uuid.trim();
	}
}
