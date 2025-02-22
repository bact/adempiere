/******************************************************************************
 * Product: Adempiere ERP & CRM Smart Business Solution                       *
 * Copyright (C) 1999-2006 ComPiere, Inc. All Rights Reserved.                *
 * This program is free software; you can redistribute it and/or modify it    *
 * under the terms version 2 of the GNU General Public License as published   *
 * by the Free Software Foundation. This program is distributed in the hope   *
 * that it will be useful, but WITHOUT ANY WARRANTY; without even the implied *
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.           *
 * See the GNU General Public License for more details.                       *
 * You should have received a copy of the GNU General Public License along    *
 * with this program; if not, write to the Free Software Foundation, Inc.,    *
 * 59 Temple Place, Suite 330, Boston, MA 02111-1307 USA.                     *
 * For the text or an alternative of this public license, you may reach us    *
 * ComPiere, Inc., 2620 Augustine Dr. #245, Santa Clara, CA 95054, USA        *
 * or via info@compiere.org or http://www.compiere.org/license.html           *
 *****************************************************************************/
package org.compiere.process;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Properties;
import java.util.logging.Level;

import org.adempiere.core.domains.models.X_I_Payment;
import org.compiere.model.MBankAccount;
import org.compiere.model.MPayment;
import org.compiere.util.AdempiereUserError;
import org.compiere.util.DB;
import org.compiere.util.Env;

/**
 * 	Import Payments
 *	
 *  @author Jorg Janke
 *  @version $Id: ImportPayment.java,v 1.2 2006/07/30 00:51:01 jjanke Exp $
 *  
 *  Contributor(s):
 *    Carlos Ruiz - globalqss - FR [ 1992542 ] Import Payment doesn't have DocAction parameter
 */
public class ImportPayment extends SvrProcess
{
	/**	Organization to be imported to	*/
	private int				p_AD_Org_ID = 0;
	/** Default Bank Account			*/
	private int				p_C_BankAccount_ID = 0;
	/**	Delete old Imported				*/
	private boolean			p_deleteOldImported = false;
	/**	Document Action					*/
	private String			m_docAction = null;

	/** Properties						*/
	private Properties 		m_ctx;

	/**
	 *  Prepare - e.g., get Parameters.
	 */
	protected void prepare()
	{
		ProcessInfoParameter[] para = getParameter();
		for (int i = 0; i < para.length; i++)
		{
			String name = para[i].getParameterName();
			if (name.equals("AD_Org_ID"))
				p_AD_Org_ID = ((BigDecimal)para[i].getParameter()).intValue();
			else if (name.equals("C_BankAccount_ID"))
				p_C_BankAccount_ID = ((BigDecimal)para[i].getParameter()).intValue();
			else if (name.equals("DeleteOldImported"))
				p_deleteOldImported = "Y".equals(para[i].getParameter());
			else if (name.equals("DocAction"))
				m_docAction = (String)para[i].getParameter();
			else
				log.log(Level.SEVERE, "Unknown Parameter: " + name);
		}
		m_ctx = Env.getCtx();
	}	//	prepare

	/**
	 * 	Process
	 *	@return info
	 *	@throws Exception
	 */
	protected String doIt() throws Exception
	{
		log.info("C_BankAccount_ID" + p_C_BankAccount_ID);
		MBankAccount ba = MBankAccount.get(getCtx(), p_C_BankAccount_ID);
		if (p_C_BankAccount_ID == 0 || ba.get_ID() != p_C_BankAccount_ID)
			throw new AdempiereUserError("@NotFound@ @C_BankAccount_ID@ - " + p_C_BankAccount_ID);
		if (p_AD_Org_ID != ba.getAD_Org_ID() && ba.getAD_Org_ID() != 0)
			p_AD_Org_ID = ba.getAD_Org_ID();
		log.info("AD_Org_ID=" + p_AD_Org_ID);
		
		StringBuffer sql = null;
		int no = 0;
		String clientCheck = " AND AD_Client_ID=" + ba.getAD_Client_ID();

		//	****	Prepare	****

		//	Delete Old Imported
		if (p_deleteOldImported)
		{
			sql = new StringBuffer ("DELETE I_Payment "
				  + "WHERE I_IsImported='Y'").append (clientCheck);
			no = DB.executeUpdate(sql.toString(), get_TrxName());
			log.fine("Delete Old Impored =" + no);
		}

		//	Set Client, Org, IsActive, Created/Updated
		sql = new StringBuffer ("UPDATE I_Payment "
			  + "SET AD_Client_ID = COALESCE (AD_Client_ID,").append (ba.getAD_Client_ID()).append ("),"
			  + " AD_Org_ID = COALESCE (AD_Org_ID,").append (p_AD_Org_ID).append ("),");
		sql.append(" IsActive = COALESCE (IsActive, 'Y'),"
			  + " Created = COALESCE (Created, SysDate),"
			  + " CreatedBy = COALESCE (CreatedBy, 0),"
			  + " Updated = COALESCE (Updated, SysDate),"
			  + " UpdatedBy = COALESCE (UpdatedBy, 0),"
			  + " I_ErrorMsg = ' ',"
			  + " I_IsImported = ? "
			  + "WHERE I_IsImported<>? OR I_IsImported IS NULL OR AD_Client_ID IS NULL OR AD_Org_ID IS NULL OR AD_Client_ID=0 OR AD_Org_ID=0");
		no = DB.executeUpdate(sql.toString(), new Object[] {"N", "Y"}, false, get_TrxName());
		log.info ("Reset=" + no);

		sql = new StringBuffer ("UPDATE I_Payment o "
			+ "SET I_IsImported=?, I_ErrorMsg=I_ErrorMsg||'ERR=Invalid Org, '"
			+ "WHERE (AD_Org_ID IS NULL OR AD_Org_ID=0"
			+ " OR EXISTS (SELECT * FROM AD_Org oo WHERE o.AD_Org_ID=oo.AD_Org_ID AND (oo.IsSummary='Y' OR oo.IsActive='N')))"
			+ " AND I_IsImported<>?").append (clientCheck);
		no = DB.executeUpdate(sql.toString(), new Object[] {"E", "Y"}, false, get_TrxName());
		if (no != 0)
			log.warning ("Invalid Org=" + no);
			
		//	Set Bank Account
		sql = new StringBuffer("UPDATE I_Payment i "
			+ "SET C_BankAccount_ID="
			+ "( "
			+ " SELECT C_BankAccount_ID "
			+ " FROM C_BankAccount a, C_Bank b "
			+ " WHERE b.IsOwnBank='Y' "
			+ " AND a.AD_Client_ID=i.AD_Client_ID "
			+ " AND a.C_Bank_ID=b.C_Bank_ID "
			+ " AND a.AccountNo=i.BankAccountNo "
			+ " AND b.RoutingNo=i.RoutingNo "
			+ " OR b.SwiftCode=i.RoutingNo "
			+ ") "
			+ "WHERE i.C_BankAccount_ID IS NULL "
			+ "AND i.I_IsImported<>? "
			+ "OR i.I_IsImported IS NULL").append(clientCheck);
		no = DB.executeUpdate(sql.toString(), new Object[] {"Y"}, false, get_TrxName());
		if (no != 0)
			log.info("Bank Account (With Routing No)=" + no);
		//
		sql = new StringBuffer("UPDATE I_Payment i " 
		 	+ "SET C_BankAccount_ID="
			+ "( "
			+ " SELECT C_BankAccount_ID "
			+ " FROM C_BankAccount a, C_Bank b "
			+ " WHERE b.IsOwnBank='Y' "
			+ " AND a.C_Bank_ID=b.C_Bank_ID " 
			+ " AND a.AccountNo=i.BankAccountNo "
			+ " AND a.AD_Client_ID=i.AD_Client_ID "
			+ ") "
			+ "WHERE i.C_BankAccount_ID IS NULL "
			+ "AND i.I_isImported<>? "
			+ "OR i.I_isImported IS NULL").append(clientCheck);
		no = DB.executeUpdate(sql.toString(), new Object[] {"Y"}, false, get_TrxName());
		if (no != 0)
			log.info("Bank Account (Without Routing No)=" + no);
		//
		sql = new StringBuffer("UPDATE I_Payment i "
			+ "SET C_BankAccount_ID=(SELECT C_BankAccount_ID FROM C_BankAccount a WHERE a.C_BankAccount_ID=").append(p_C_BankAccount_ID);
		sql.append(" and a.AD_Client_ID=i.AD_Client_ID) "
			+ "WHERE i.C_BankAccount_ID IS NULL "
			+ "AND i.BankAccountNo IS NULL "
			+ "AND i.I_isImported<>? "
			+ "OR i.I_isImported IS NULL").append(clientCheck);
		no = DB.executeUpdate(sql.toString(), new Object[] {"Y"}, false, get_TrxName());
		if (no != 0)
			log.info("Bank Account=" + no);
		//	
		sql = new StringBuffer("UPDATE I_Payment "
			+ "SET I_isImported=?, I_ErrorMsg=I_ErrorMsg||'ERR=Invalid Bank Account, ' "
			+ "WHERE C_BankAccount_ID IS NULL "
			+ "AND I_isImported<>? "
			+ "OR I_isImported IS NULL").append(clientCheck);
		no = DB.executeUpdate(sql.toString(), new Object[] {"E", "Y"}, false, get_TrxName());
		if (no != 0)
			log.warning("Invalid Bank Account=" + no);
		 
		//	Set Currency
		sql = new StringBuffer ("UPDATE I_Payment i "
			+ "SET C_Currency_ID=(SELECT C_Currency_ID FROM C_Currency c"
			+ " WHERE i.ISO_Code=c.ISO_Code AND c.AD_Client_ID IN (0,i.AD_Client_ID)) "
			+ "WHERE C_Currency_ID IS NULL"
			+ " AND I_IsImported<>?").append(clientCheck);
		no = DB.executeUpdate(sql.toString(), new Object[] {"Y"}, false, get_TrxName());
		if (no != 0)
			log.info("Set Currency=" + no);
		//
		sql = new StringBuffer("UPDATE I_Payment i "
			+ "SET C_Currency_ID=(SELECT C_Currency_ID FROM C_BankAccount WHERE C_BankAccount_ID=i.C_BankAccount_ID) "
			+ "WHERE i.C_Currency_ID IS NULL "
			+ "AND i.ISO_Code IS NULL").append(clientCheck);
		no = DB.executeUpdate(sql.toString(), get_TrxName());
		if (no != 0)
			log.info("Set Currency=" + no);
		//
		sql = new StringBuffer ("UPDATE I_Payment "
			+ "SET I_IsImported=?, I_ErrorMsg=I_ErrorMsg||'ERR=No Currency,' "
			+ "WHERE C_Currency_ID IS NULL "
			+ "AND I_IsImported<>? "
			+ " AND I_IsImported<>?").append(clientCheck);
		no = DB.executeUpdate(sql.toString(), new Object[] {"E", "E", "Y"}, false, get_TrxName());
		if (no != 0)
			log.warning("No Currency=" + no);
		 
		//	Set Amount
		sql = new StringBuffer("UPDATE I_Payment "
		 	+ "SET ChargeAmt=0 "
			+ "WHERE ChargeAmt IS NULL "
			+ "AND I_IsImported<>?").append(clientCheck);
		no = DB.executeUpdate(sql.toString(), new Object[] {"Y"}, false, get_TrxName());
		if (no != 0)
			log.info("Charge Amount=" + no);
		//
		sql = new StringBuffer("UPDATE I_Payment "
		 	+ "SET TaxAmt=0 "
			+ "WHERE TaxAmt IS NULL "
			+ "AND I_IsImported<>?").append(clientCheck);
		no = DB.executeUpdate(sql.toString(), new Object[] {"Y"}, false, get_TrxName());
		if (no != 0)
			log.info("Tax Amount=" + no);
		//
		sql = new StringBuffer("UPDATE I_Payment "
			+ "SET WriteOffAmt=0 "
			+ "WHERE WriteOffAmt IS NULL "
			+ "AND I_IsImported<>?").append(clientCheck);
		no = DB.executeUpdate(sql.toString(), new Object[] {"Y"}, false, get_TrxName());
		if (no != 0)
			log.info("WriteOff Amount=" + no);
		//
		sql = new StringBuffer("UPDATE I_Payment "
			+ "SET DiscountAmt=0 "
			+ "WHERE DiscountAmt IS NULL "
			+ "AND I_IsImported<>'Y'").append(clientCheck);
		no = DB.executeUpdate(sql.toString(), get_TrxName());
		if (no != 0)
			log.info("Discount Amount=" + no);
		//
			
		//	Set Date
		sql = new StringBuffer("UPDATE I_Payment "
		 	+ "SET DateTrx=Created "
			+ "WHERE DateTrx IS NULL "
			+ "AND I_isImported<>?").append(clientCheck);
		no = DB.executeUpdate(sql.toString(), new Object[] {"Y"}, false, get_TrxName());
		if (no != 0)
			log.info("Trx Date=" + no);
		
		sql = new StringBuffer("UPDATE I_Payment "
		 	+ "SET DateAcct=DateTrx "
			+ "WHERE DateAcct IS NULL "
			+ "AND I_isImported<>?").append(clientCheck);
		no = DB.executeUpdate(sql.toString(), new Object[] {"Y"}, false, get_TrxName());
		if (no != 0)
			log.info("Acct Date=" + no);
		
		//	Invoice
		sql = new StringBuffer ("UPDATE I_Payment i "
			  + "SET C_Invoice_ID=(SELECT MAX(C_Invoice_ID) FROM C_Invoice ii"
			  + " WHERE i.InvoiceDocumentNo=ii.DocumentNo AND i.AD_Client_ID=ii.AD_Client_ID) "
			  + "WHERE C_Invoice_ID IS NULL AND InvoiceDocumentNo IS NOT NULL"
			  + " AND I_IsImported<>?").append (clientCheck);
		no = DB.executeUpdate(sql.toString(), new Object[] {"Y"}, false, get_TrxName());
		if (no != 0)
			log.fine("Set Invoice from DocumentNo=" + no);
		
		//	BPartner
		sql = new StringBuffer ("UPDATE I_Payment i "
			  + "SET C_BPartner_ID=(SELECT MAX(C_BPartner_ID) FROM C_BPartner bp"
			  + " WHERE i.BPartnerValue=bp.Value AND i.AD_Client_ID=bp.AD_Client_ID) "
			  + "WHERE C_BPartner_ID IS NULL AND BPartnerValue IS NOT NULL"
			  + " AND I_IsImported<>?").append (clientCheck);
		no = DB.executeUpdate(sql.toString(), new Object[] {"Y"}, false, get_TrxName());
		if (no != 0)
			log.fine("Set BP from Value=" + no);
		
		sql = new StringBuffer ("UPDATE I_Payment i "
			  + "SET C_BPartner_ID=(SELECT MAX(C_BPartner_ID) FROM C_Invoice ii"
			  + " WHERE i.C_Invoice_ID=ii.C_Invoice_ID AND i.AD_Client_ID=ii.AD_Client_ID) "
			  + "WHERE C_BPartner_ID IS NULL AND C_Invoice_ID IS NOT NULL"
			  + " AND I_IsImported<>?").append (clientCheck);
		no = DB.executeUpdate(sql.toString(), new Object[] {"Y"}, false, get_TrxName());
		if (no != 0)
			log.fine("Set BP from Invoice=" + no);
		
		sql = new StringBuffer ("UPDATE I_Payment "
			+ "SET I_IsImported=?, I_ErrorMsg=I_ErrorMsg||'ERR=No BPartner,' "
			+ "WHERE C_BPartner_ID IS NULL "
			+ "AND I_IsImported<>? "
			+ " AND I_IsImported<>?").append(clientCheck);
		no = DB.executeUpdate(sql.toString(), new Object[] {"E", "E", "Y"}, false, get_TrxName());
		if (no != 0)
			log.warning("No BPartner=" + no);
		
		// Charge - begin - https://adempiere.atlassian.net/browse/ADEMPIERE-170
		sql = new StringBuffer ("UPDATE I_Payment i "
			  + "SET C_Charge_ID=(SELECT MAX(C_Charge_ID) FROM C_Charge charge"
			  + " WHERE i.ChargeName=charge.Name AND i.AD_Client_ID=charge.AD_Client_ID) "
			  + "WHERE C_Charge_ID IS NULL AND ChargeName IS NOT NULL"
			  + " AND I_IsImported<>?").append (clientCheck);
		no = DB.executeUpdate(sql.toString(), new Object[] {"Y"}, false, get_TrxName());
		if (no != 0)
			log.fine("Set Charge from Name=" + no);
		
		sql = new StringBuffer ("UPDATE I_Payment "
			+ "SET I_IsImported=?, I_ErrorMsg=I_ErrorMsg||'ERR=No Charge,' "
			+ "WHERE C_Charge_ID IS NULL "
			+ " AND I_IsImported<>? "
			+ " AND ChargeName IS NOT NULL "
			+ " AND I_IsImported<>?").append(clientCheck);
		no = DB.executeUpdate(sql.toString(), new Object[] {"E", "E", "Y"}, false, get_TrxName());
		if (no != 0)
			log.warning("No ChargeName=" + no);
		// Charge - end
		
		//	Check Payment<->Invoice combination
		sql = new StringBuffer("UPDATE I_Payment "
			+ "SET I_IsImported=?, I_ErrorMsg=I_ErrorMsg||'Err=Invalid Payment<->Invoice, ' "
			+ "WHERE I_Payment_ID IN "
				+ "(SELECT I_Payment_ID "
				+ "FROM I_Payment i"
				+ " INNER JOIN C_Payment p ON (i.C_Payment_ID=p.C_Payment_ID) "
				+ "WHERE i.C_Invoice_ID IS NOT NULL "
				+ " AND p.C_Invoice_ID IS NOT NULL "
				+ " AND p.C_Invoice_ID<>i.C_Invoice_ID) ")
			.append(clientCheck);
		no = DB.executeUpdate(sql.toString(), new Object[] {"E"}, false, get_TrxName());
		if (no != 0)
			log.info("Payment<->Invoice Mismatch=" + no);
			
		//	Check Payment<->BPartner combination
		sql = new StringBuffer("UPDATE I_Payment "
			+ "SET I_IsImported=?, I_ErrorMsg=I_ErrorMsg||'Err=Invalid Payment<->BPartner, ' "
			+ "WHERE I_Payment_ID IN "
				+ "(SELECT I_Payment_ID "
				+ "FROM I_Payment i"
				+ " INNER JOIN C_Payment p ON (i.C_Payment_ID=p.C_Payment_ID) "
				+ "WHERE i.C_BPartner_ID IS NOT NULL "
				+ " AND p.C_BPartner_ID IS NOT NULL "
				+ " AND p.C_BPartner_ID<>i.C_BPartner_ID) ")
			.append(clientCheck);
		no = DB.executeUpdate(sql.toString(), new Object[] {"E"}, false, get_TrxName());
		if (no != 0)
			log.info("Payment<->BPartner Mismatch=" + no);
			
		//	Check Invoice<->BPartner combination
		sql = new StringBuffer("UPDATE I_Payment "
			+ "SET I_IsImported=?, I_ErrorMsg=I_ErrorMsg||'Err=Invalid Invoice<->BPartner, ' "
			+ "WHERE I_Payment_ID IN "
				+ "(SELECT I_Payment_ID "
				+ "FROM I_Payment i"
				+ " INNER JOIN C_Invoice v ON (i.C_Invoice_ID=v.C_Invoice_ID) "
				+ "WHERE i.C_BPartner_ID IS NOT NULL "
				+ " AND v.C_BPartner_ID IS NOT NULL "
				+ " AND v.C_BPartner_ID<>i.C_BPartner_ID) ")
			.append(clientCheck);
		no = DB.executeUpdate(sql.toString(), new Object[] {"E"}, false, get_TrxName());
		if (no != 0)
			log.info("Invoice<->BPartner Mismatch=" + no);
			
		//	Check Invoice.BPartner<->Payment.BPartner combination
		sql = new StringBuffer("UPDATE I_Payment "
			+ "SET I_IsImported=?, I_ErrorMsg=I_ErrorMsg||'Err=Invalid Invoice.BPartner<->Payment.BPartner, ' "
			+ "WHERE I_Payment_ID IN "
				+ "(SELECT I_Payment_ID "
				+ "FROM I_Payment i"
				+ " INNER JOIN C_Invoice v ON (i.C_Invoice_ID=v.C_Invoice_ID)"
				+ " INNER JOIN C_Payment p ON (i.C_Payment_ID=p.C_Payment_ID) "
				+ "WHERE p.C_Invoice_ID<>v.C_Invoice_ID"
				+ " AND v.C_BPartner_ID<>p.C_BPartner_ID) ")
			.append(clientCheck);
		no = DB.executeUpdate(sql.toString(), new Object[] {"E"}, false, get_TrxName());
		if (no != 0)
			log.info("Invoice.BPartner<->Payment.BPartner Mismatch=" + no);
			
		//	TrxType
		sql = new StringBuffer("UPDATE I_Payment "
			+ "SET TrxType='S' "	//	MPayment.TRXTYPE_Sales
			+ "WHERE TrxType IS NULL "
			+ "AND I_IsImported<>?").append(clientCheck);
		no = DB.executeUpdate(sql.toString(), new Object[] {"Y"}, false, get_TrxName());
		if (no != 0)
			log.info("TrxType Default=" + no);
		
		//	TenderType
		sql = new StringBuffer("UPDATE I_Payment "
			+ "SET TenderType='K' "	//	MPayment.TENDERTYPE_Check
			+ "WHERE TenderType IS NULL "
			+ "AND I_IsImported<>?").append(clientCheck);
		no = DB.executeUpdate(sql.toString(), new Object[] {"Y"}, false, get_TrxName());
		if (no != 0)
			log.info("TenderType Default=" + no);

		//	Document Type
		sql = new StringBuffer ("UPDATE I_Payment i "
			  + "SET C_DocType_ID=(SELECT C_DocType_ID FROM C_DocType d WHERE d.Name=i.DocTypeName"
			  + " AND d.DocBaseType IN ('ARR','APP') AND i.AD_Client_ID=d.AD_Client_ID) "
			  + "WHERE C_DocType_ID IS NULL AND DocTypeName IS NOT NULL AND I_IsImported<>?").append (clientCheck);
		no = DB.executeUpdate(sql.toString(), new Object[] {"Y"}, false, get_TrxName());
		if (no != 0)
			log.fine("Set DocType=" + no);
		sql = new StringBuffer ("UPDATE I_Payment "
			  + "SET I_IsImported=?, I_ErrorMsg=I_ErrorMsg||'ERR=Invalid DocTypeName, ' "
			  + "WHERE C_DocType_ID IS NULL AND DocTypeName IS NOT NULL"
			  + " AND I_IsImported<>?").append (clientCheck);
		no = DB.executeUpdate(sql.toString(), new Object[] {"E", "Y"}, false, get_TrxName());
		if (no != 0)
			log.warning ("Invalid DocTypeName=" + no);
		sql = new StringBuffer ("UPDATE I_Payment "
			  + "SET I_IsImported=?, I_ErrorMsg=I_ErrorMsg||'ERR=No DocType, ' "
			  + "WHERE C_DocType_ID IS NULL"
			  + " AND I_IsImported<>?").append (clientCheck);
		no = DB.executeUpdate(sql.toString(), new Object[] {"E", "Y"}, false, get_TrxName());
		if (no != 0)
			log.warning ("No DocType=" + no);
		
		//	Set Conversion Type
		sql = new StringBuffer("UPDATE I_Payment i "
				+ "SET ConversionTypeValue='S' "
				+ " WHERE C_ConversionType_ID IS NULL AND ConversionTypeValue IS NULL "
				+ " AND I_IsImported=? ").append(clientCheck);
		no = DB.executeUpdate(sql.toString(), new Object[] {"N"}, false, get_TrxName());
		log.fine("Set CurrencyType Value to Spot =" + no);
		
		sql = new StringBuffer("UPDATE I_Payment i "
				+ "SET C_ConversionType_ID=(SELECT c.C_ConversionType_ID FROM C_ConversionType c "
				+ " WHERE c.Value=i.ConversionTypeValue AND c.AD_Client_ID IN (0,i.AD_Client_ID)) "
				+ " WHERE C_ConversionType_ID IS NULL AND ConversionTypeValue IS NOT NULL"
				+ " AND I_IsImported<>? ").append(clientCheck);
		no = DB.executeUpdate(sql.toString(), new Object[] {"Y"}, false, get_TrxName());
		log.fine("Set CurrencyType from Value=" + no);
		
		sql = new StringBuffer("UPDATE I_Payment i "
				+ " SET I_IsImported=?, I_ErrorMsg=COALESCE(I_ErrorMsg,'')	||' ERR=Invalid CurrencyType, ' "
				+ " WHERE (C_ConversionType_ID IS NULL OR C_ConversionType_ID=0) AND ConversionTypeValue IS NOT NULL "
				+ " AND I_IsImported<>? ").append(clientCheck);
		no = DB.executeUpdate(sql.toString(), new Object[] {"E", "Y"}, false, get_TrxName());
		if (no != 0)
			log.warning("Invalid CurrencyTypeValue=" + no);

		commitEx();
		
		//Import Bank Statement
		sql = new StringBuffer("SELECT * FROM I_Payment"
			+ " WHERE I_IsImported='N'"
			+ " ORDER BY C_BankAccount_ID, CheckNo, DateTrx, R_AuthCode");
			
		MBankAccount account = null;
		PreparedStatement pstmt = null;
		int noInsert = 0;
		try
		{
			pstmt = DB.prepareStatement(sql.toString(), get_TrxName());
			ResultSet rs = pstmt.executeQuery();
				
			while (rs.next())
			{ 
				X_I_Payment imp = new X_I_Payment(m_ctx, rs, get_TrxName());
				//	Get the bank account
				if (account == null || account.getC_BankAccount_ID() != imp.getC_BankAccount_ID())
				{
					account = MBankAccount.get (m_ctx, imp.getC_BankAccount_ID());
					log.info("New Account=" + account.getAccountNo());
				}
				
				//	New Payment
				MPayment payment = new MPayment (m_ctx, 0, get_TrxName());
				payment.setAD_Org_ID(imp.getAD_Org_ID());
				payment.setDocumentNo(imp.getDocumentNo());
				payment.setPONum(imp.getPONum());
				
				payment.setTrxType(imp.getTrxType());
				payment.setTenderType(imp.getTenderType());
				
				payment.setC_BankAccount_ID(imp.getC_BankAccount_ID());
				payment.setRoutingNo(imp.getRoutingNo());
				payment.setAccountNo(imp.getAccountNo());
				payment.setCheckNo(imp.getCheckNo());
				payment.setMicr(imp.getMicr());
				
				if (imp.getCreditCardType() != null)
					payment.setCreditCardType(imp.getCreditCardType());
				payment.setCreditCardNumber(imp.getCreditCardNumber());
				if (imp.getCreditCardExpMM() != 0)
					payment.setCreditCardExpMM(imp.getCreditCardExpMM());
				if (imp.getCreditCardExpYY() != 0)
					payment.setCreditCardExpYY(imp.getCreditCardExpYY());
				payment.setCreditCardVV(imp.getCreditCardVV());
				payment.setSwipe(imp.getSwipe());
				
				payment.setDateAcct(imp.getDateAcct());
				payment.setDateTrx(imp.getDateTrx());
				payment.setDescription(imp.get_ValueAsString(MPayment.COLUMNNAME_Description));
				//
				payment.setC_BPartner_ID(imp.getC_BPartner_ID());
				payment.setC_Invoice_ID(imp.getC_Invoice_ID());
				payment.setC_DocType_ID(imp.getC_DocType_ID());
				payment.setC_Currency_ID(imp.getC_Currency_ID());
				payment.setC_ConversionType_ID(imp.get_ValueAsInt(MPayment.COLUMNNAME_C_ConversionType_ID));
				payment.setC_Charge_ID(imp.getC_Charge_ID());
				payment.setChargeAmt(imp.getChargeAmt());
				payment.setTaxAmt(imp.getTaxAmt());
				
				payment.setPayAmt(imp.getPayAmt());
				payment.setWriteOffAmt(imp.getWriteOffAmt());
				payment.setDiscountAmt(imp.getDiscountAmt());
				payment.setWriteOffAmt(imp.getWriteOffAmt());
				
				//	Copy statement line reference data
				payment.setA_City(imp.getA_City());
				payment.setA_Country(imp.getA_Country());
				payment.setA_EMail(imp.getA_EMail());
				payment.setA_Ident_DL(imp.getA_Ident_DL());
				payment.setA_Ident_SSN(imp.getA_Ident_SSN());
				payment.setA_Name(imp.getA_Name());
				payment.setA_State(imp.getA_State());
				payment.setA_Street(imp.getA_Street());
				payment.setA_Zip(imp.getA_Zip());
				payment.setR_AuthCode(imp.getR_AuthCode());
				payment.setR_Info(imp.getR_Info());
				payment.setR_PnRef(imp.getR_PnRef());
				payment.setR_RespMsg(imp.getR_RespMsg());
				payment.setR_Result(imp.getR_Result());
				payment.setOrig_TrxID(imp.getOrig_TrxID());
				payment.setVoiceAuthCode(imp.getVoiceAuthCode());
				
				//	Save payment
				if (payment.save())
				{
					imp.setC_Payment_ID(payment.getC_Payment_ID());
					imp.setI_IsImported(true);
					imp.setProcessed(true);
					imp.saveEx();
					noInsert++;

					if (payment != null && m_docAction != null && m_docAction.length() > 0)
					{
						payment.setDocAction(m_docAction);
						payment.processIt (m_docAction);
						payment.saveEx();
					}
				}
				
			}
			
			//	Close database connection
			rs.close();
			pstmt.close();
			rs = null;
			pstmt = null;

		}
		catch(Exception e)
		{
			log.log(Level.SEVERE, sql.toString(), e);
		}
		
		//	Set Error to indicator to not imported
		sql = new StringBuffer ("UPDATE I_Payment "
			+ "SET I_IsImported='N', Updated=SysDate "
			+ "WHERE I_IsImported<>'Y'").append(clientCheck);
		no = DB.executeUpdate(sql.toString(), get_TrxName());
		addLog (0, null, new BigDecimal (no), "@Errors@");
		//
		addLog (0, null, new BigDecimal (noInsert), "@C_Payment_ID@: @Inserted@");
		return "";
	}	//	doIt
	
}	//	ImportPayment
