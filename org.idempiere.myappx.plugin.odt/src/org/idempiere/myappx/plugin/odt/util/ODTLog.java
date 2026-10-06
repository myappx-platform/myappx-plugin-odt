/**********************************************************************
 * Copyright (C) Contributors                                          *
 * Contributors: ken.longnan@gmail.com                               *
 **********************************************************************/

package org.idempiere.myappx.plugin.odt.util;

import java.util.logging.Level;

import org.compiere.util.CLogger;

/**
 * Centralized logging helpers for the ODT plugin.
 * <p>
 * All messages use the {@code [ODT][Module]} prefix and {@code key=value} pairs
 * so they are easy to grep and analyze in server logs.
 */
public final class ODTLog
{
	private static final String PREFIX = "[ODT]";

	/** Bundle activator (META-INF/ODTPackage.xml). */
	public static final String MOD_ACTIVATOR = "Activator";
	/** ODT + 2Pack combined bundle activator. */
	public static final String MOD_2PACK = "2Pack";
	/** Install pipeline ({@code ODTInstallEngine}). */
	public static final String MOD_INSTALL = "Install";
	/** Version export / refresh ({@code MKSODTVersion}). */
	public static final String MOD_EXPORT = "Export";
	/** Version gate ({@code ODTVersionCheck}). */
	public static final String MOD_VERSION = "Version";
	/** Column DDL sync ({@code ODTDdlService}). */
	public static final String MOD_DDL = "DDL";
	/** Table index validation ({@code ODTIndexService}). */
	public static final String MOD_INDEX = "Index";
	/** Post-install orchestration ({@code ODTPostInstallService}). */
	public static final String MOD_POST_INSTALL = "PostInstall";
	/** Process attachment import/export. */
	public static final String MOD_ATTACHMENT = "Attachment";
	/** Lookup resolution ({@code ODTLookupHelper}). */
	public static final String MOD_LOOKUP = "Lookup";
	/** PO build / reconcile ({@code ODTPOBuilder}, {@code ODTPOHelper}). */
	public static final String MOD_PO = "PO";
	/** Query helpers ({@code ODTQueryHelper}). */
	public static final String MOD_QUERY = "Query";
	/** iDempiere processes ({@code SvrProcess}). */
	public static final String MOD_PROCESS = "Process";
	/** ODT package model ({@code MKSODTPackage}). */
	public static final String MOD_PACKAGE = "Package";

	private ODTLog()
	{
	}

	/** Format {@code [ODT][Module] message}. */
	public static String format(String module, String message)
	{
		if (module == null || module.isEmpty())
			return PREFIX + " " + message;
		return PREFIX + "[" + module + "] " + message;
	}

	/** Single {@code key=value} fragment. */
	public static String kv(String key, Object value)
	{
		return key + "=" + (value == null ? "null" : value);
	}

	/**
	 * Join {@code key=value} pairs with {@code |}.
	 * Arguments must be even-length: {@code key1, val1, key2, val2, ...}.
	 */
	public static String kvs(Object... keyValues)
	{
		if (keyValues == null || keyValues.length == 0)
			return "";
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i + 1 < keyValues.length; i += 2)
		{
			if (sb.length() > 0)
				sb.append(" | ");
			sb.append(kv(String.valueOf(keyValues[i]), keyValues[i + 1]));
		}
		return sb.toString();
	}

	public static void fine(CLogger log, String module, String message)
	{
		if (log != null && log.isLoggable(Level.FINE))
			log.fine(format(module, message));
	}

	public static void info(CLogger log, String module, String message)
	{
		if (log != null && log.isLoggable(Level.INFO))
			log.info(format(module, message));
	}

	public static void warning(CLogger log, String module, String message)
	{
		if (log != null && log.isLoggable(Level.WARNING))
			log.warning(format(module, message));
	}

	public static void severe(CLogger log, String module, String message)
	{
		if (log != null)
			log.log(Level.SEVERE, format(module, message));
	}

	public static void severe(CLogger log, String module, String message, Throwable t)
	{
		if (log != null)
			log.log(Level.SEVERE, format(module, message), t);
		else if (t != null)
			CLogger.get().log(Level.SEVERE, format(module, message), t);
	}

	/** Log at SEVERE when {@code log} is null (fallback to default logger). */
	public static void severeOrDefault(CLogger log, String module, String message, Throwable t)
	{
		if (log != null)
			log.log(Level.SEVERE, format(module, message), t);
		else
			CLogger.get().log(Level.SEVERE, format(module, message), t);
	}
}
