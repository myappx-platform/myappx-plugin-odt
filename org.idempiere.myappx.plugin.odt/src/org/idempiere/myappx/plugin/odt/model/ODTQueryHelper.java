/**********************************************************************
 * Copyright (C) Contributors                                          *
 * Contributors: ken.longnan@gmail.com                               *
 **********************************************************************/

package org.idempiere.myappx.plugin.odt.model;

import java.util.List;
import java.util.logging.Level;

import org.adempiere.exceptions.AdempiereException;
import org.compiere.model.MTable;
import org.compiere.model.PO;
import org.compiere.model.Query;
import org.compiere.util.CLogger;

import org.idempiere.myappx.plugin.odt.util.ODTLog;

/** Query-based PO listing for ODT. */
public final class ODTQueryHelper
{
	private ODTQueryHelper()
	{
	}

	/**
	 * List POs for a table using the Query API.
	 *
	 * @param table table metadata
	 * @param whereClause optional where clause (may use {@code ?} placeholders)
	 * @param params bind parameters for where clause, or null
	 * @param orderClause optional order by clause
	 * @param trxName transaction name
	 * @param log logger for errors
	 * @return matching POs
	 * @throws AdempiereException when the query fails
	 */
	public static PO[] listPOs(MTable table, String whereClause, Object[] params, String orderClause,
			String trxName, CLogger log)
	{
		try
		{
			Query query = new Query(table.getCtx(), table.getTableName(), whereClause, trxName);
			if (params != null)
				query.setParameters(params);
			if (orderClause != null && !orderClause.isEmpty())
				query.setOrderBy(orderClause);
			List<PO> list = query.list();
			return list.toArray(new PO[list.size()]);
		}
		catch (Exception e)
		{
			String msg = "listPOs failed | " + ODTLog.kvs("table", table.getTableName(),
					"where", whereClause, "order", orderClause);
			ODTLog.severe(log, ODTLog.MOD_QUERY, msg, e);
			throw new AdempiereException(msg, e);
		}
	}
}
