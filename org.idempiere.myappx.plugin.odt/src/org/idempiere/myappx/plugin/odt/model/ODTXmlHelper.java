/**********************************************************************
 * Copyright (C) Contributors                                          *
 * Contributors: ken.longnan@gmail.com                               *
 **********************************************************************/

package org.idempiere.myappx.plugin.odt.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import javax.xml.XMLConstants;

import org.compiere.model.I_AD_TableIndex;
import org.compiere.model.MColumn;
import org.compiere.model.MTable;
import org.compiere.model.X_AD_EntityType;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/** XML helpers for ODT package export and install. */
public final class ODTXmlHelper
{
	private ODTXmlHelper()
	{
	}

	/**
	 * Append a CDATA child and mark {@code xml:space="preserve"} so Transformer indent
	 * does not inject whitespace into the value (same pattern as iDempiere {@code PO.getDocument()}).
	 * Import still reads {@code getTextContent()} as-is; do not trim {@code NewValue}.
	 *
	 * @return the created child element
	 */
	public static Element appendCDataChild(Document document, Element parent, String tagName, String value)
	{
		Element el = document.createElement(tagName);
		el.setAttributeNS(XMLConstants.XML_NS_URI, "xml:space", "preserve");
		el.appendChild(document.createCDATASection(value == null ? "" : value));
		parent.appendChild(el);
		return el;
	}

	/** Collect {@link I_AD_TableIndex} UUIDs from an ODTVersion XML element (bundle install). */
	public static List<String> collectTableIndexUuidsFromVersionXml(Properties ctx, Element eODTVersion)
	{
		List<String> uuids = new ArrayList<>();
		if (eODTVersion == null)
			return uuids;

		int tableIndexTableId = MTable.get(ctx, I_AD_TableIndex.Table_Name).getAD_Table_ID();
		String tableIndexTableIdStr = String.valueOf(tableIndexTableId);
		NodeList childrenOD = eODTVersion.getElementsByTagName("ODTObjectData");
		for (int i = 0; i < childrenOD.getLength(); i++)
		{
			Element eODTOD = (Element) childrenOD.item(i);
			if (!tableIndexTableIdStr.equals(eODTOD.getAttribute(X_KS_ODTObjectData.COLUMNNAME_AD_Table_ID)))
				continue;
			String uuid = eODTOD.getAttribute(X_KS_ODTObjectData.COLUMNNAME_ObjectData_UUID);
			if (uuid != null && !uuid.trim().isEmpty())
				uuids.add(uuid.trim());
		}
		return uuids;
	}

	/**
	 * Resolve EntityType from AD_EntityType ObjectData inside an ODTVersion XML element.
	 *
	 * @param ctx context
	 * @param eODTVersion ODTVersion element from ODTPackage.xml
	 * @return EntityType value or null when not found
	 */
	public static String extractEntityTypeFromVersionXml(Properties ctx, Element eODTVersion)
	{
		if (eODTVersion == null)
			return null;

		int entityTypeTableId = MTable.get(ctx, X_AD_EntityType.Table_Name).getAD_Table_ID();
		int entityTypeColumnId = MColumn.getColumn_ID(X_AD_EntityType.Table_Name, X_AD_EntityType.COLUMNNAME_EntityType);
		String entityTypeTableIdStr = String.valueOf(entityTypeTableId);
		String entityTypeColumnIdStr = String.valueOf(entityTypeColumnId);

		NodeList childrenOD = eODTVersion.getElementsByTagName("ODTObjectData");
		for (int i = 0; i < childrenOD.getLength(); i++)
		{
			Element eODTOD = (Element) childrenOD.item(i);
			if (!entityTypeTableIdStr.equals(eODTOD.getAttribute(X_KS_ODTObjectData.COLUMNNAME_AD_Table_ID)))
				continue;

			NodeList childrenODL = eODTOD.getElementsByTagName("ODTObjectDataLine");
			for (int ii = 0; ii < childrenODL.getLength(); ii++)
			{
				Element eODTODL = (Element) childrenODL.item(ii);
				if (!entityTypeColumnIdStr.equals(eODTODL.getAttribute(X_KS_ODTObjectDataLine.COLUMNNAME_AD_Column_ID)))
					continue;

				Node newValueNode = eODTODL.getElementsByTagName(X_KS_ODTObjectDataLine.COLUMNNAME_NewValue).item(0);
				if (newValueNode == null)
					continue;

				String value = newValueNode.getTextContent();
				if (value != null && !value.trim().isEmpty())
					return value.trim();
			}
		}
		return null;
	}
}
