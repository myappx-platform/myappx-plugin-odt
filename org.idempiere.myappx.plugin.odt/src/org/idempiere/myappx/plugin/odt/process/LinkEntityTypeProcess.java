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

package org.idempiere.myappx.plugin.odt.process;

import java.util.logging.Level;

import org.compiere.process.ProcessInfoParameter;
import org.compiere.process.SvrProcess;
import org.compiere.util.AdempiereSystemError;

import org.idempiere.myappx.plugin.odt.model.MKSODTPackage;
import org.idempiere.myappx.plugin.odt.model.MKSODTVersion;
import org.idempiere.myappx.plugin.odt.util.ODTLog;

@org.adempiere.base.annotation.Process
public class LinkEntityTypeProcess extends SvrProcess
{
	/** Entity Type			*/
	private String	p_EntityType = "C";	//	ENTITYTYPE_Customization

	private int p_ODTVersion_ID = 0;

	private MKSODTVersion odtVersion;

	@Override
	protected void prepare()
	{
		ProcessInfoParameter[] para = getParameter();
		for (int i = 0; i < para.length; i++)
		{
			String name = para[i].getParameterName();
			if (para[i].getParameter() == null)
				;
			else if (name.equals("EntityType"))
				p_EntityType = (String)para[i].getParameter();
			else
				ODTLog.severe(log, ODTLog.MOD_PROCESS, "Unknown process parameter | "
						+ ODTLog.kvs("name", name));
		}

		p_ODTVersion_ID = getRecord_ID();
	}

	@Override
	protected String doIt() throws Exception
	{
		if (p_ODTVersion_ID == 0)
			throw new AdempiereSystemError("@NotFound@ @KS_ODTVersion_ID@ " + p_ODTVersion_ID);
		ODTLog.info(log, ODTLog.MOD_PROCESS, "LinkEntityType started | "
				+ ODTLog.kvs("KS_ODTVersion_ID", p_ODTVersion_ID, "entityType", p_EntityType));

		try {
			odtVersion = new MKSODTVersion(getCtx(), p_ODTVersion_ID, get_TrxName());
			odtVersion.linkEntityType(p_EntityType);

			MKSODTPackage odtPackage = new MKSODTPackage(getCtx(), odtVersion.getKS_ODTPackage_ID(), get_TrxName());
			if (odtPackage.get_ID() == 0)
				throw new AdempiereSystemError("@NotFound@ @KS_ODTPackage_ID@ " + odtVersion.getKS_ODTPackage_ID());
			odtPackage.setEntityType(p_EntityType);
			odtPackage.saveEx();
		}
		catch (Exception e)
		{
			ODTLog.severe(log, ODTLog.MOD_PROCESS, "LinkEntityType failed | "
					+ ODTLog.kvs("KS_ODTVersion_ID", p_ODTVersion_ID, "entityType", p_EntityType), e);
			throw e;
		}

		ODTLog.info(log, ODTLog.MOD_PROCESS, "LinkEntityType completed | "
				+ ODTLog.kvs("KS_ODTVersion_ID", p_ODTVersion_ID, "entityType", p_EntityType));

		return "#" + "ODT Version Link EntityType Successful!";
	}

}
