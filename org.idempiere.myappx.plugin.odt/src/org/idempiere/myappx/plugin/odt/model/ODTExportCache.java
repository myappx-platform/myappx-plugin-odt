/**********************************************************************
 * Copyright (C) Contributors                                          *
 * Contributors: ken.longnan@gmail.com                               *
 **********************************************************************/

package org.idempiere.myappx.plugin.odt.model;

import java.util.HashMap;
import java.util.Map;

import org.compiere.model.MColumn;
import org.compiere.model.MTable;
import org.compiere.model.PO;

/**
 * Lookup cache for one Version Refresh (or link-entity-type export) on the current thread.
 * Identifier and parent columns are queried once per table. Each referenced row is loaded once.
 */
public final class ODTExportCache
{
	private static final ThreadLocal<ODTExportCache> CURRENT = new ThreadLocal<>();

	private final String trxName;
	private final Map<Integer, MColumn[]> identifierColumns = new HashMap<>();
	private final Map<Integer, MColumn[]> parentColumns = new HashMap<>();
	private final Map<String, Ref> refs = new HashMap<>();

	private ODTExportCache(String trxName)
	{
		this.trxName = trxName;
	}

	public static void begin(String trxName)
	{
		CURRENT.set(new ODTExportCache(trxName));
	}

	public static void end()
	{
		CURRENT.remove();
	}

	public static ODTExportCache current()
	{
		return CURRENT.get();
	}

	public String trxName()
	{
		return trxName;
	}

	public MColumn[] identifierColumns(int tableId)
	{
		return identifierColumns.get(tableId);
	}

	public void setIdentifierColumns(int tableId, MColumn[] columns)
	{
		identifierColumns.put(tableId, columns);
	}

	public MColumn[] parentColumns(int tableId)
	{
		return parentColumns.get(tableId);
	}

	public void setParentColumns(int tableId, MColumn[] columns)
	{
		parentColumns.put(tableId, columns);
	}

	public PO load(MTable table, int recordId)
	{
		Ref ref = ref(table.getAD_Table_ID(), recordId);
		if (ref.loaded)
			return ref.po;
		ref.po = table.getPO(recordId, trxName);
		ref.loaded = true;
		return ref.po;
	}

	public boolean hasIdValue(int tableId, int recordId)
	{
		Ref ref = refs.get(key(tableId, recordId));
		return ref != null && ref.idValueReady;
	}

	public String idValue(int tableId, int recordId)
	{
		return refs.get(key(tableId, recordId)).idValue;
	}

	public void storeIdValue(int tableId, int recordId, String idValue)
	{
		Ref ref = ref(tableId, recordId);
		ref.idValue = idValue;
		ref.idValueReady = true;
	}

	private Ref ref(int tableId, int recordId)
	{
		return refs.computeIfAbsent(key(tableId, recordId), k -> new Ref());
	}

	private static String key(int tableId, int recordId)
	{
		return tableId + ":" + recordId;
	}

	private static final class Ref
	{
		private boolean loaded;
		private PO po;
		private boolean idValueReady;
		private String idValue;
	}
}
