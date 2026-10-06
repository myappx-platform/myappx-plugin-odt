/**********************************************************************
 * This file is part of iDempiere ERP Open Source                      *
 * http://www.idempiere.org                                            *
 **********************************************************************/

package org.idempiere.myappx.plugin.odt.model;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.Base64;
import java.util.Properties;
import java.util.logging.Level;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.adempiere.exceptions.AdempiereException;
import org.compiere.model.I_AD_Process;
import org.compiere.model.MAttachment;
import org.compiere.model.MAttachmentEntry;
import org.compiere.model.MClientInfo;
import org.compiere.model.MStorageProvider;
import org.compiere.model.MTable;
import org.compiere.model.PO;
import org.compiere.util.CLogger;
import org.compiere.util.Util;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import org.idempiere.myappx.plugin.odt.util.ODTLog;

/**
 * Export/import {@link MAttachment} as Base64-encoded ZIP embedded in ODT XML ({@code AttachmentPayload}).
 */
public final class AttachmentODTUtil
{
	public static final String XML_ATTR_ENCODING = "encoding";
	public static final String XML_ENCODING_BASE64 = "base64";

	private AttachmentODTUtil()
	{
	}

	/**
	 * @param attachment source attachment (entries loaded)
	 * @return Base64-encoded ZIP of all entries, or null if empty
	 */
	public static String exportZipAsBase64(MAttachment attachment)
	{
		if (attachment == null || attachment.getEntryCount() < 1)
			return null;

		byte[] zipBytes = readAttachmentZipBytes(attachment);
		if (zipBytes == null || zipBytes.length == 0)
			return null;

		return Base64.getEncoder().encodeToString(zipBytes);
	}

	private static byte[] readAttachmentZipBytes(MAttachment attachment)
	{
		attachment.getEntries();
		File zipFile = null;
		try
		{
			zipFile = attachment.saveAsZip();
			if (zipFile == null || !zipFile.exists())
				return null;
			return Files.readAllBytes(zipFile.toPath());
		}
		catch (IOException e)
		{
			throw new AdempiereException("Failed to read attachment as zip", e);
		}
		finally
		{
			if (zipFile != null)
				zipFile.delete();
		}
	}

	/**
	 * Restore attachment entries from Base64 ZIP payload.
	 */
	public static void importZipFromBase64(MAttachment attachment, String base64Payload, CLogger log)
	{
		if (Util.isEmpty(base64Payload))
			return;

		byte[] zipBytes;
		try
		{
			zipBytes = Base64.getDecoder().decode(base64Payload.trim());
		}
		catch (IllegalArgumentException e)
		{
			throw new AdempiereException("Invalid Base64 attachment payload", e);
		}

		attachment.getEntries();
		try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes)))
		{
			ZipEntry entry;
			while ((entry = zis.getNextEntry()) != null)
			{
				if (entry.isDirectory())
					continue;
				String name = entry.getName();
				if (Util.isEmpty(name))
					continue;
				// strip path prefix from zip tools
				int slash = name.lastIndexOf('/');
				if (slash >= 0)
					name = name.substring(slash + 1);
				int backslash = name.lastIndexOf('\\');
				if (backslash >= 0)
					name = name.substring(backslash + 1);

				byte[] data = readZipEntryBytes(zis);
				if (data == null || data.length == 0)
					continue;

				boolean found = false;
				int index = -1;
				for (MAttachmentEntry existing : attachment.getEntries())
				{
					index++;
					if (existing.getName().equals(name))
					{
						found = true;
						attachment.updateEntry(index, data);
						break;
					}
				}
				if (!found)
					attachment.addEntry(name, data);
				zis.closeEntry();
			}
		}
		catch (IOException e)
		{
			throw new AdempiereException("Failed to import attachment from zip payload", e);
		}

		if (log != null && log.isLoggable(Level.FINE))
			ODTLog.fine(log, ODTLog.MOD_ATTACHMENT, "Imported attachment entries | "
					+ ODTLog.kvs("entryCount", attachment.getEntryCount()));
	}

	private static byte[] readZipEntryBytes(InputStream in) throws IOException
	{
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		byte[] buf = new byte[8192];
		int n;
		while ((n = in.read(buf)) > 0)
			out.write(buf, 0, n);
		return out.toByteArray();
	}

	/**
	 * Install attachment for {@link I_AD_Process} from ODT object data.
	 */
	public static void installProcessAttachment(Properties ctx, MKSODTObjectData odtod, String trxName, CLogger log)
	{
		String processUUID = odtod.getObjectData_UUID();
		String payload = odtod.getAttachmentPayload();
		if (Util.isEmpty(processUUID) || Util.isEmpty(payload))
		{
			ODTLog.warning(log, ODTLog.MOD_ATTACHMENT, "Skip attachment install: missing UUID or payload | "
					+ ODTLog.kvs("name", odtod.getName(), "uuid", processUUID));
			return;
		}

		MTable processTable = MTable.get(ctx, I_AD_Process.Table_Name);
		PO process = ODTPOHelper.findPO(processUUID, processTable, trxName);
		if (process == null || process.get_ID() <= 0)
			throw new AdempiereException("AD_Process not found for attachment install, UUID=" + processUUID);

		String recordUU = process.get_ValueAsString(process.getUUIDColumnName());
		MAttachment att = MAttachment.get(ctx, process.get_Table_ID(), process.get_ID(), recordUU, trxName);
		if (att == null)
			att = new MAttachment(ctx, process.get_Table_ID(), process.get_ID(), recordUU, trxName);

		importZipFromBase64(att, payload, log);
		if (!att.save(trxName))
			throw new AdempiereException("Failed to save attachment for AD_Process UUID=" + processUUID);
	}

	/**
	 * Install process attachment from {@code ODTObjectData} XML element (embedded {@code AttachmentPayload}).
	 */
	public static void installProcessAttachmentFromXml(Properties ctx, Element element, CLogger log)
	{
		String processUUID = element.getAttribute(X_KS_ODTObjectData.COLUMNNAME_ObjectData_UUID);
		String payload = readAttachmentPayloadFromXml(element);
		if (Util.isEmpty(processUUID) || Util.isEmpty(payload))
		{
			ODTLog.warning(log, ODTLog.MOD_ATTACHMENT, "Skip attachment XML install: missing UUID or payload | "
					+ ODTLog.kvs("uuid", processUUID));
			return;
		}

		MTable processTable = MTable.get(ctx, I_AD_Process.Table_Name);
		PO process = ODTPOHelper.findPO(processUUID, processTable, null);
		if (process == null || process.get_ID() <= 0)
			throw new AdempiereException("AD_Process not found for attachment install, UUID=" + processUUID);

		String recordUU = process.get_ValueAsString(process.getUUIDColumnName());
		MAttachment att = MAttachment.get(ctx, process.get_Table_ID(), process.get_ID(), recordUU, null);
		if (att == null)
			att = new MAttachment(ctx, process.get_Table_ID(), process.get_ID(), recordUU, null);

		importZipFromBase64(att, payload, log);
		att.saveEx();
	}

	public static String readAttachmentPayloadFromXml(Element element)
	{
		Node payloadNode = (Element)element.getElementsByTagName(X_KS_ODTObjectData.COLUMNNAME_AttachmentPayload).item(0);
		if (payloadNode == null)
			return null;
		return payloadNode.getTextContent().trim();
	}

	/** @return true if client stores attachments outside DB (filesystem etc.) */
	public static boolean usesExternalAttachmentStorage(Properties ctx)
	{
		MClientInfo ci = MClientInfo.get(ctx, 0);
		if (ci == null || ci.getAD_StorageProvider_ID() <= 0)
			return false;
		MStorageProvider sp = MStorageProvider.get(ctx, ci.getAD_StorageProvider_ID());
		return sp != null && !MStorageProvider.METHOD_Database.equals(sp.getMethod());
	}
}
