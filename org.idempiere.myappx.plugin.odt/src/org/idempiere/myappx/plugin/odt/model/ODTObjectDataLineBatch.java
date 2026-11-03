/**********************************************************************
 * Copyright (C) Contributors                                          *
 * Contributors: ken.longnan@gmail.com                               *
 **********************************************************************/

package org.idempiere.myappx.plugin.odt.model;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.UUID;

import org.adempiere.exceptions.AdempiereException;
import org.compiere.model.MSequence;
import org.compiere.model.MSysConfig;
import org.compiere.util.CLogger;
import org.compiere.util.DB;
import org.compiere.util.Env;
import org.compiere.util.Ini;
import org.compiere.util.Trx;
import org.compiere.util.Util;

import org.idempiere.myappx.plugin.odt.util.ODTLog;

/**
 * Batched insert of {@code KS_ODTObjectDataLine}. The line has no model logic, so this skips
 * {@code PO.saveEx} while still taking IDs from the same sequence {@code MSequence} uses.
 */
public final class ODTObjectDataLineBatch implements AutoCloseable
{
	static final int BATCH_SIZE = 400;

	private static final String INSERT_SQL =
			"INSERT INTO KS_ODTObjectDataLine ("
			+ "KS_ODTObjectDataLine_ID, KS_ODTObjectDataLine_UU, "
			+ "AD_Client_ID, AD_Org_ID, IsActive, Created, CreatedBy, Updated, UpdatedBy, "
			+ "KS_ODTObjectData_ID, AD_Column_ID, "
			+ "IsNewNullValue, IsOldNullValue, NewValue, OldValue, NewID, OldID, NewUUID, OldUUID"
			+ ") VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";

	private final CLogger log = CLogger.getCLogger(ODTObjectDataLineBatch.class);
	private final Properties ctx;
	private final String trxName;
	private final int clientId;
	private final int orgId;
	private final int userId;
	private final Timestamp now;
	private final ArrayDeque<Integer> ids = new ArrayDeque<>();
	private PreparedStatement batch;
	private int pending;
	private int inserted;

	public ODTObjectDataLineBatch(Properties ctx, String trxName)
	{
		this.ctx = ctx != null ? ctx : Env.getCtx();
		this.trxName = trxName;
		this.clientId = Env.getAD_Client_ID(this.ctx);
		this.orgId = Env.getAD_Org_ID(this.ctx);
		this.userId = Env.getAD_User_ID(this.ctx);
		this.now = new Timestamp(System.currentTimeMillis());
	}

	public int getInserted()
	{
		return inserted;
	}

	/**
	 * @param lineUuid line UUID from XML; generated when null or blank
	 */
	public void add(int objectDataId, int columnId,
			boolean isNewNull, boolean isOldNull,
			String newValue, String oldValue,
			int newId, int oldId,
			String newUuid, String oldUuid,
			String lineUuid)
	{
		try
		{
			PreparedStatement ps = statement();
			int i = 1;
			ps.setInt(i++, nextId());
			bindUuid(ps, i++, lineUuid == null || lineUuid.isBlank()
					? Util.generateUUIDv7().toString() : lineUuid);
			ps.setInt(i++, clientId);
			ps.setInt(i++, orgId);
			ps.setString(i++, "Y");
			ps.setTimestamp(i++, now);
			ps.setInt(i++, userId);
			ps.setTimestamp(i++, now);
			ps.setInt(i++, userId);
			ps.setInt(i++, objectDataId);
			ps.setInt(i++, columnId);
			ps.setString(i++, isNewNull ? "Y" : "N");
			ps.setString(i++, isOldNull ? "Y" : "N");
			ps.setString(i++, newValue);
			ps.setString(i++, oldValue);
			ps.setInt(i++, newId);
			ps.setInt(i++, oldId);
			ps.setString(i++, newUuid);
			ps.setString(i++, oldUuid);
			ps.addBatch();
			pending++;
			if (pending >= BATCH_SIZE)
				flush();
		}
		catch (SQLException e)
		{
			throw new AdempiereException("ODT object data line batch failed", e);
		}
	}

	public void flush()
	{
		if (batch == null || pending == 0)
			return;
		try
		{
			batch.executeBatch();
			batch.clearBatch();
			inserted += pending;
			pending = 0;
		}
		catch (SQLException e)
		{
			throw new AdempiereException("ODT object data line batch failed", e);
		}
	}

	@Override
	public void close()
	{
		try
		{
			flush();
		}
		finally
		{
			DB.close(batch);
			batch = null;
			pending = 0;
		}
		if (inserted > 0)
			ODTLog.info(log, ODTLog.MOD_EXPORT, "Inserted object data lines | "
					+ ODTLog.kvs("count", inserted));
	}

	private PreparedStatement statement()
	{
		if (batch == null)
			batch = DB.prepareStatement(INSERT_SQL, trxName);
		return batch;
	}

	/** {@code KS_ODTObjectDataLine_UU} is a PostgreSQL uuid column; a varchar bind is rejected. */
	private static void bindUuid(PreparedStatement ps, int index, String uuid) throws SQLException
	{
		if (DB.isPostgreSQL())
			ps.setObject(index, UUID.fromString(uuid));
		else
			ps.setString(index, uuid);
	}

	private int nextId()
	{
		if (ids.isEmpty())
		{
			for (int id : reserve(I_KS_ODTObjectDataLine.Table_Name, BATCH_SIZE))
				ids.add(id);
		}
		Integer id = ids.poll();
		if (id == null || id.intValue() <= 0)
			throw new AdempiereException("Cannot allocate ID for " + I_KS_ODTObjectDataLine.Table_Name);
		return id.intValue();
	}

	private List<Integer> reserve(String tableName, int count)
	{
		if (isAdempiereSys())
			return reserveOneByOne(tableName, count, null);
		if (MSysConfig.getBooleanValue(MSysConfig.SYSTEM_NATIVE_SEQUENCE, false))
		{
			try
			{
				return nextvals(sequenceName(tableName), count);
			}
			catch (Exception e)
			{
				ODTLog.info(log, ODTLog.MOD_EXPORT, "Native sequence reserve failed, using MSequence | "
						+ ODTLog.kvs("table", tableName, "error", e.getMessage()));
				return reserveOneByOne(tableName, count, e);
			}
		}
		return reserveAdSequence(tableName, count);
	}

	private List<Integer> reserveOneByOne(String tableName, int count, Exception cause)
	{
		List<Integer> reserved = new ArrayList<>(count);
		for (int i = 0; i < count; i++)
		{
			int id = MSequence.getNextID(clientId, tableName, null);
			if (id <= 0)
				throw new AdempiereException("Cannot allocate ID for " + tableName, cause);
			reserved.add(id);
		}
		return reserved;
	}

	private List<Integer> reserveAdSequence(String tableName, int count)
	{
		String select = "SELECT CurrentNext, IncrementNo, AD_Sequence_ID FROM AD_Sequence "
				+ "WHERE Name=? AND IsActive='Y' AND IsTableID='Y' AND IsAutoSequence='Y' ";
		if (DB.isOracle())
			select += "FOR UPDATE OF CurrentNext";
		else
			select += "FOR UPDATE";

		java.sql.Connection conn = null;
		PreparedStatement selectStmt = null;
		PreparedStatement updateStmt = null;
		ResultSet rs = null;
		try
		{
			conn = DB.getConnection(false);
			selectStmt = conn.prepareStatement(select);
			selectStmt.setString(1, tableName);
			rs = selectStmt.executeQuery();
			if (!rs.next())
				throw new AdempiereException("No AD_Sequence for " + tableName);
			int current = rs.getInt(1);
			int increment = rs.getInt(2);
			if (increment <= 0)
				increment = 1;
			int sequenceId = rs.getInt(3);
			DB.close(rs);
			rs = null;

			updateStmt = conn.prepareStatement(
					"UPDATE AD_Sequence SET CurrentNext=CurrentNext+? WHERE AD_Sequence_ID=?");
			updateStmt.setInt(1, count * increment);
			updateStmt.setInt(2, sequenceId);
			updateStmt.executeUpdate();
			conn.commit();

			List<Integer> reserved = new ArrayList<>(count);
			int id = current;
			for (int i = 0; i < count; i++)
			{
				reserved.add(id);
				id += increment;
			}
			return reserved;
		}
		catch (AdempiereException e)
		{
			rollback(conn);
			throw e;
		}
		catch (Exception e)
		{
			rollback(conn);
			throw new AdempiereException("Cannot reserve AD_Sequence for " + tableName, e);
		}
		finally
		{
			DB.close(rs);
			DB.close(updateStmt);
			DB.close(selectStmt);
			if (conn != null)
			{
				try
				{
					conn.close();
				}
				catch (SQLException e)
				{
					// connection return failed; the reserve already committed or rolled back
				}
			}
		}
	}

	private List<Integer> nextvals(String sequence, int count) throws SQLException
	{
		String sql;
		if (DB.isOracle())
			sql = "SELECT " + sequence.toUpperCase(Locale.ROOT) + ".NEXTVAL FROM DUAL CONNECT BY LEVEL <= " + count;
		else
			sql = "SELECT nextval('" + sequence.toLowerCase(Locale.ROOT) + "') FROM generate_series(1, " + count + ")";

		String seqTrxName = Trx.createTrxName("ODTSeq");
		Trx trx = Trx.get(seqTrxName, true);
		PreparedStatement ps = null;
		ResultSet rs = null;
		try
		{
			ps = DB.prepareStatement(sql, seqTrxName);
			rs = ps.executeQuery();
			List<Integer> reserved = new ArrayList<>(count);
			while (rs.next())
				reserved.add(rs.getInt(1));
			if (reserved.size() != count)
				throw new SQLException("Expected " + count + " sequence values for " + sequence
						+ ", got " + reserved.size());
			if (!trx.commit())
				throw new SQLException("Commit failed while reserving " + sequence);
			return reserved;
		}
		catch (SQLException e)
		{
			rollback(trx);
			throw e;
		}
		finally
		{
			DB.close(rs, ps);
			trx.close();
		}
	}

	private static String sequenceName(String tableName)
	{
		if (tableName == null || !tableName.matches("[A-Za-z0-9_]+"))
			throw new AdempiereException("Invalid table name for sequence: " + tableName);
		return tableName + "_SQ";
	}

	private boolean isAdempiereSys()
	{
		boolean adempiereSys;
		if (Ini.isClient())
			adempiereSys = Ini.isPropertyBool(Ini.P_ADEMPIERESYS);
		else
		{
			String sysProperty = ctx.getProperty(Ini.P_ADEMPIERESYS, "N");
			adempiereSys = "y".equalsIgnoreCase(sysProperty) || "true".equalsIgnoreCase(sysProperty);
		}
		if (adempiereSys && clientId > 11)
			adempiereSys = false;
		return adempiereSys;
	}

	private static void rollback(java.sql.Connection conn)
	{
		if (conn == null)
			return;
		try
		{
			conn.rollback();
		}
		catch (SQLException e)
		{
			// the caller reports the original failure
		}
	}

	private static void rollback(Trx trx)
	{
		if (trx == null)
			return;
		try
		{
			trx.rollback();
		}
		catch (Exception e)
		{
			// the caller reports the original failure
		}
	}
}
