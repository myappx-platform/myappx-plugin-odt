/**********************************************************************
 * This file is part of iDempiere ERP Open Source                      *
 * http://www.idempiere.org                                            *
 *                                                                     *
 * Copyright (C) Contributors                                          *
 *                                                                     *
 * This program is free software; you can redistribute it and/or       *
 * modify it under the terms of the GNU General Public License         *
 * as published by the Free Software Foundation; either version 2      *
 * of the License, or (at your option) any later version.              *
 *                                                                     *
 * This program is distributed in the hope that it will be useful,     *
 * but WITHOUT ANY WARRANTY; without even the implied warranty of      *
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the        *
 * GNU General Public License for more details.                        *
 *                                                                     *
 * You should have received a copy of the GNU General Public License   *
 * along with this program; if not, write to the Free Software         *
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston,          *
 * MA 02110-1301, USA.                                                 *
 *                                                                     *
 * Contributors:                                                       *
 * - ken.longnan@gmail.com                                             *
 **********************************************************************/

package org.idempiere.myappx.plugin.odt.model;

import java.sql.ResultSet;
import java.util.Properties;

import org.compiere.util.Trx;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

public class MKSODTObjectDataLine extends X_KS_ODTObjectDataLine {

	/**
	 *
	 */
	private static final long serialVersionUID = 4754625137516875474L;

	public MKSODTObjectDataLine(Properties ctx, int KS_ODTObjectDataLine_ID,
			String trxName) {
		super(ctx, KS_ODTObjectDataLine_ID, trxName);
	}

	public MKSODTObjectDataLine(Properties ctx, ResultSet rs, String trxName) {
		super(ctx, rs, trxName);
	}

	public MKSODTObjectDataLine(MKSODTObjectData parent, MKSODTObjectDataLine from) {
		this (parent.getCtx(), 0, parent.get_TrxName());
		copyValues(from, this);
		setClientOrg(parent);
		setKS_ODTObjectData_ID(parent.getKS_ODTObjectData_ID());
	}

	public Node toXmlNode(Document document)
	{
		Element elemntodtodl = document.createElement("ODTObjectDataLine");
		elemntodtodl.setAttribute("ID", String.valueOf(get_ID()));
		elemntodtodl.setAttribute("UUID", get_Value(getUUIDColumnName()).toString());
		elemntodtodl.setAttribute(COLUMNNAME_AD_Column_ID, String.valueOf(getAD_Column_ID()));
		
		// TODO: ???
		// for special Column, like Help/Comments, should be createCDATASection for NewValue/OldValue
		// check AD_Column and AD_Reference, especially for "Text" type
		// Example:
		//		<ODTObjectDataLine AD_Column_ID="2824" ID="1010365" UUID="98d0581c-b951-4127-a22e-cabb2f1b2ebc">
		//        <NewValue>Name of a file in the local directory space - or URL (file://.., http://.., ftp://..)</NewValue>
		//        <OldValue/>
		//        <IsNewNullValue>false</IsNewNullValue>
		//        <IsOldNullValue>false</IsOldNullValue>
		//        <NewID>0</NewID>
		//        <OldID>0</OldID>
		//        <NewUUID/>
		//        <OldUUID/>
		//      </ODTObjectDataLine>
		

		ODTXmlHelper.appendCDataChild(document, elemntodtodl, COLUMNNAME_NewValue, getNewValue());
		ODTXmlHelper.appendCDataChild(document, elemntodtodl, COLUMNNAME_OldValue, getOldValue());

		Element elemnt = null;
		elemnt = document.createElement(COLUMNNAME_IsNewNullValue);
		elemnt.appendChild(document.createTextNode(String.valueOf(isNewNullValue())));
		elemntodtodl.appendChild(elemnt);

		elemnt = document.createElement(COLUMNNAME_IsOldNullValue);
		elemnt.appendChild(document.createTextNode(String.valueOf(isOldNullValue())));
		elemntodtodl.appendChild(elemnt);

		elemnt = document.createElement(COLUMNNAME_NewID);
		elemnt.appendChild(document.createTextNode(String.valueOf(getNewID())));
		elemntodtodl.appendChild(elemnt);

		elemnt = document.createElement(COLUMNNAME_OldID);
		elemnt.appendChild(document.createTextNode(String.valueOf(getOldID())));
		elemntodtodl.appendChild(elemnt);

		elemnt = document.createElement(COLUMNNAME_NewUUID);
		elemnt.appendChild(document.createTextNode(getNewUUID() == null? "":getNewUUID()));
		elemntodtodl.appendChild(elemnt);

		elemnt = document.createElement(COLUMNNAME_OldUUID);
		elemnt.appendChild(document.createTextNode(getOldUUID() == null? "":getOldUUID()));
		elemntodtodl.appendChild(elemnt);

		return elemntodtodl;
	}

	public static MKSODTObjectDataLine fromXmlNode(Element element, int ODTObjectDat_ID, Properties ctx) {
		int id = Integer.valueOf(element.getAttribute("ID"));
		String uuid = element.getAttribute("UUID");
		String AD_Column_ID = element.getAttribute(COLUMNNAME_AD_Column_ID);
		Node NewValue = (Element)element.getElementsByTagName(COLUMNNAME_NewValue).item(0);
		Node OldValue = (Element)element.getElementsByTagName(COLUMNNAME_OldValue).item(0);
		Node IsNewNullValue = (Element)element.getElementsByTagName(COLUMNNAME_IsNewNullValue).item(0);
		Node IsOldNullValue = (Element)element.getElementsByTagName(COLUMNNAME_IsOldNullValue).item(0);
		Node NewID = (Element)element.getElementsByTagName(COLUMNNAME_NewID).item(0);
		Node OldID = (Element)element.getElementsByTagName(COLUMNNAME_OldID).item(0);
		Node NewUUID = (Element)element.getElementsByTagName(COLUMNNAME_NewUUID).item(0);
		Node OldUUID = (Element)element.getElementsByTagName(COLUMNNAME_OldUUID).item(0);

		MKSODTObjectDataLine odtodl = new MKSODTObjectDataLine(ctx, 0, null);
		odtodl.setKS_ODTObjectData_ID(ODTObjectDat_ID);
		odtodl.setAD_Column_ID(Integer.valueOf(AD_Column_ID));
		odtodl.setNewValue(NewValue.getTextContent());
		odtodl.setOldValue(OldValue.getTextContent());
		odtodl.setIsNewNullValue(Boolean.valueOf(IsNewNullValue.getTextContent()));
		odtodl.setIsOldNullValue(Boolean.valueOf(IsOldNullValue.getTextContent()));
		odtodl.setNewID(Integer.valueOf(NewID.getTextContent()));
		odtodl.setOldID(Integer.valueOf(OldID.getTextContent()));
		odtodl.setNewUUID(NewUUID.getTextContent());
		odtodl.setOldUUID(OldUUID.getTextContent());
		odtodl.set_ValueNoCheck(odtodl.getUUIDColumnName(), uuid);

		return odtodl;
	}


	public static void importFromXmlNode(MKSODTObjectData odtod, Element element) {
		MKSODTObjectDataLine odtodl = MKSODTObjectDataLine.fromXmlNode(element, odtod.get_ID(), odtod.getCtx());
		odtodl.saveEx();
	}
}
