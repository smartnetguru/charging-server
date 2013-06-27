/*
 * TeleStax, Open Source Cloud Communications
 * Copyright 2012, TeleStax and individual contributors as indicated
 * by the @authors tag. See the copyright.txt in the distribution for
 * a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.mobicents.charging.server;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

import javax.slee.ActivityContextInterface;
import javax.slee.ChildRelation;
import javax.slee.CreateException;
import javax.slee.InitialEventSelector;
import javax.slee.SLEEException;
import javax.slee.Sbb;
import javax.slee.SbbContext;
import javax.slee.TransactionRequiredLocalException;
import javax.slee.facilities.TimerEvent;
import javax.slee.facilities.TimerFacility;
import javax.slee.facilities.TimerOptions;
import javax.slee.facilities.TimerPreserveMissed;
import javax.slee.facilities.Tracer;
import javax.slee.resource.ResourceAdaptorTypeID;
import javax.slee.serviceactivity.ServiceStartedEvent;

import net.java.slee.resource.diameter.base.events.avp.DiameterResultCode;
import net.java.slee.resource.diameter.cca.events.avp.CcRequestType;
import net.java.slee.resource.diameter.cca.events.avp.CreditControlResultCode;
import net.java.slee.resource.diameter.cca.events.avp.GrantedServiceUnitAvp;
import net.java.slee.resource.diameter.cca.events.avp.MultipleServicesCreditControlAvp;
import net.java.slee.resource.diameter.cca.events.avp.RequestedServiceUnitAvp;
import net.java.slee.resource.diameter.cca.events.avp.SubscriptionIdAvp;
import net.java.slee.resource.diameter.cca.events.avp.UsedServiceUnitAvp;
import net.java.slee.resource.diameter.ro.RoActivityContextInterfaceFactory;
import net.java.slee.resource.diameter.ro.RoAvpFactory;
import net.java.slee.resource.diameter.ro.RoProvider;
import net.java.slee.resource.diameter.ro.RoServerSessionActivity;
import net.java.slee.resource.diameter.ro.events.RoCreditControlAnswer;
import net.java.slee.resource.diameter.ro.events.RoCreditControlRequest;

import org.mobicents.charging.server.account.AccountBalanceManagement;
import org.mobicents.charging.server.account.UnitReservation;
import org.mobicents.charging.server.account.UnitReservation.ErrorCodeType;
import org.mobicents.charging.server.data.DataSource;
import org.mobicents.slee.ChildRelationExt;
import org.mobicents.slee.SbbContextExt;
import org.mobicents.slee.SbbLocalObjectExt;

/**
 * Diameter Charging Server Root SBB.
 * 
 * @author ammendonca
 * @author baranowb
 */
public abstract class DiameterChargingServerSbb extends BaseSbb implements Sbb/*Ext*/, DiameterChargingServer {

	private static final long DEFAULT_VALIDITY_TIME = 86400;	
	private static final TimerOptions DEFAULT_TIMER_OPTIONS = new TimerOptions(0, TimerPreserveMissed.ALL);

	private static TimerOptions createDefaultTimerOptions() {
		TimerOptions timerOptions = new TimerOptions();
		timerOptions.setPreserveMissed(TimerPreserveMissed.ALL);
		return timerOptions;
	}

	private SbbContextExt sbbContextExt; // This SBB's SbbContext

	private Tracer tracer;
	private TimerFacility timerFacility;

	private RoAvpFactory avpFactory;
	private RoActivityContextInterfaceFactory roAcif;
	private RoProvider roProvider;

	// ---------------------------- SLEE Callbacks ----------------------------

	public void setSbbContext(SbbContext context) {
		this.sbbContextExt = (SbbContextExt) context;
		this.tracer = sbbContextExt.getTracer("CS-Core");
		this.timerFacility = this.sbbContextExt.getTimerFacility();

		ResourceAdaptorTypeID raTypeID = new ResourceAdaptorTypeID("Diameter Ro", "java.net", "0.8.1");
		this.roProvider = (RoProvider) sbbContextExt.getResourceAdaptorInterface(raTypeID , "DiameterRo");
		this.roAcif = (RoActivityContextInterfaceFactory) sbbContextExt.getActivityContextInterfaceFactory(raTypeID);

		this.avpFactory = this.roProvider.getRoAvpFactory();
	}

	public void unsetSbbContext() {
		this.sbbContextExt = null;
		this.tracer = null;
	}

	/**
	 * Convenience method to retrieve the SbbContext object stored in
	 * setSbbContext.
	 * 
	 * TODO: If your SBB doesn't require the SbbContext object you may remove
	 * this method, the sbbContext variable and the variable assignment in
	 * setSbbContext().
	 * 
	 * @return this SBB's SbbContext object
	 */
	protected SbbContext getSbbContext() {
		return sbbContextExt;
	}

	// ---------------------------- Child Relation ----------------------------
	public abstract ChildRelation getAccountBalanceManagementChildRelation();
	public abstract ChildRelation getDatasourceChildRelation();

	// --------------------------------- IES ----------------------------------
	public InitialEventSelector onCreditControlRequestInitialEventSelect(InitialEventSelector ies) {
		RoCreditControlRequest event = (RoCreditControlRequest) ies.getEvent();

		ies.setCustomName(event.getSessionId());
		ies.setInitialEvent(true);

		return ies;
	}

	// ---------------------------- Helper Methods ----------------------------

	private static final String DATASOURCE_CHILD_NAME = "DATASOURCE";
	protected DataSource getDatasource() throws TransactionRequiredLocalException, IllegalArgumentException, NullPointerException, SLEEException, CreateException {
		ChildRelationExt cre = (ChildRelationExt) getDatasourceChildRelation();
		SbbLocalObjectExt sbbLocalObject = cre.get(DATASOURCE_CHILD_NAME);
		if (sbbLocalObject == null) {
			sbbLocalObject = cre.create(DATASOURCE_CHILD_NAME);
		}

		return (DataSource) sbbLocalObject;
	}

	private static final String ABMF_CHILD_NAME = "ACC_MANAGER";
	protected AccountBalanceManagement getAccountManager() throws TransactionRequiredLocalException, IllegalArgumentException, NullPointerException, SLEEException, CreateException {
		ChildRelationExt cre = (ChildRelationExt) getAccountBalanceManagementChildRelation();
		SbbLocalObjectExt sbbLocalObject = cre.get(ABMF_CHILD_NAME);
		if (sbbLocalObject == null) {
			sbbLocalObject = cre.create(ABMF_CHILD_NAME);
		}

		return (AccountBalanceManagement) sbbLocalObject;
	}

	/**
	 * @param errorCodeType
	 * @return
	 */
	protected long getResultCode(ErrorCodeType errorCodeType) {
		switch(errorCodeType)
		{
		//actually return codes are not 100% ok here.
		case InvalidUser:
			return CreditControlResultCode.DIAMETER_USER_UNKNOWN;
		case BadRoamingCountry: 
			return CreditControlResultCode.DIAMETER_END_USER_SERVICE_DENIED;
		case NoServiceForUser:
			return CreditControlResultCode.DIAMETER_END_USER_SERVICE_DENIED;
		case NotEnoughBalance:
			return CreditControlResultCode.DIAMETER_CREDIT_LIMIT_REACHED;
		case InvalidContent:
		case MalformedRequest:
		case AccountingConnectionErr:
		default:
			return DiameterResultCode.DIAMETER_UNABLE_TO_DELIVER;
		}
	}

	// ---------------------------- Event Handlers ----------------------------

	public void onServiceStartedEvent(ServiceStartedEvent event, ActivityContextInterface aci) {
		if (tracer.isInfoEnabled()) {
			tracer.info("==============================================================================");
			tracer.info("==                 Mobicents Charging Server v1.0 [STARTED]                 ==");
			tracer.info("==                                  - . -                                   ==");
			tracer.info("==              Thank you for running Mobicents Community code              ==");
			tracer.info("==   For Commercial Grade Support, please request a TelScale Subscription   ==");
			tracer.info("==                         http://www.telestax.com/                         ==");
			tracer.info("==============================================================================");
		}

		DataSource ds;
		try {
			ds = getDatasource();
			if (tracer.isInfoEnabled()) {
				tracer.info("[><] Got DataSource Child SBB Local Interface [" + ds + "]");
			}
			ds.init();
		}
		catch (Exception e) {
			tracer.severe("[xx] Unable to fetch Datasource Child SBB .");
			return;
		}

		AccountBalanceManagement am;
		try {
			am = getAccountManager();
			if (tracer.isInfoEnabled()) {
				tracer.info("[><] Got Account Balance Management Child SBB Local Interface [" + am + "]");
			}
		}
		catch (Exception e) {
			tracer.severe("[xx] Unable to fetch Account and Balance Management Child SBB .");
			return;
		}

		try {
			Properties props = new Properties();
			props.load(this.getClass().getClassLoader().getResourceAsStream("users.properties"));
			for (Object key : props.keySet()) {
				String imsi = (String) key;
				// am.addUser(imsi, Long.valueOf(props.getProperty(imsi)));
				// FIXME: remove the properties to database mapping.. useful for now
				ds.updateUser(imsi, Long.valueOf(props.getProperty(imsi)), 0);
			}
			if (tracer.isInfoEnabled()) {
				tracer.info("[--] Loaded users from properties file. Dumping state.");
				am.dump("%");
			}
		}
		catch(Exception e) {
			tracer.warning("[!!] Unable to load users from properties file. Allowing everything!");
			am.setBypass(true);
		}
	}

	public void onCreditControlRequest(RoCreditControlRequest ccr, ActivityContextInterface aci) {
		String serviceContextId = "Some-Service-Context-Id";

		String sessionId = ccr.getSessionId();

		if (tracer.isInfoEnabled()) {
			tracer.info("[<<] SID<" + ccr.getSessionId() + "> Received Credit-Control-Request [" + ccr.getCcRequestType().toString() + "]");
			if(tracer.isFineEnabled()) {
				tracer.fine(ccr.toString());
			}
		}

		// Some common ops. may be moved to proper places to avoid unnecessary ops
		RoServerSessionActivity ccServerActivity = (RoServerSessionActivity) aci.getActivity();

		String endUserId = null;

		// Get the Subcription-Id and it's Type .. for now we only care for first, still we log all
		SubscriptionIdAvp[] subscriptionIds = ccr.getSubscriptionIds();

		if (subscriptionIds != null && subscriptionIds.length > 0) {
			endUserId = subscriptionIds[0].getSubscriptionIdData();
			if (tracer.isInfoEnabled()) {
				for (SubscriptionIdAvp subscriptionId : subscriptionIds) {
					tracer.info("[--] SID<" + sessionId + "> Received CCR has Subcription-Id with type '" + subscriptionId.
							getSubscriptionIdType() + "' and value '" + subscriptionId.getSubscriptionIdData() + "'.");
				}
			}
		}
		else {
			tracer.severe("[xx] SID<" + sessionId + "> Subscription-Id AVP missing in CCR. Rejecting CCR.");
			createCCA(ccServerActivity, ccr, null, DiameterResultCode.DIAMETER_MISSING_AVP);
		}

		RoCreditControlAnswer cca = null;
		if (endUserId == null) {
			tracer.severe("[xx] SID<" + sessionId + "> Subscription-Id AVP is present but could not read it's data. Rejecting CCR.");
			cca = createCCA(ccServerActivity, ccr, null, DiameterResultCode.DIAMETER_MISSING_AVP); //TODO: include missing avp - its a "SHOULD"
			try {
				ccServerActivity.sendRoCreditControlAnswer(cca);
			}
			catch (IOException e) {
				tracer.severe("[xx] SID<" + sessionId + "> Error while trying to send Credit-Control-Answer.", e);
			}
			aci.detach(this.getSbbContext().getSbbLocalObject());
			return;
		}

		AccountBalanceManagement accountBalanceManagement = null;

		switch (ccr.getCcRequestType()) {
		// INITIAL_REQUEST 1
		case INITIAL_REQUEST:
		// UPDATE_REQUEST 2
		case UPDATE_REQUEST:
			timerFacility.setTimer(aci, null, System.currentTimeMillis() + 15000, DEFAULT_TIMER_OPTIONS);

			try {
				accountBalanceManagement = getAccountManager();

				// retrieve service information from AVPs
				serviceContextId = ccr.getServiceContextId();
				if (serviceContextId == null) {
					tracer.severe("[xx] SID<" + sessionId + "> Service-Context-Id AVP missing in CCR. Rejecting CCR.");
					createCCA(ccServerActivity, ccr, null, DiameterResultCode.DIAMETER_MISSING_AVP);
				}
				else {
					if (serviceContextId.equals("")) {
						tracer.severe("[xx] SID<" + sessionId + "> Service-Context-Id AVP is empty in CCR. Rejecting CCR.");
						createCCA(ccServerActivity, ccr, null, DiameterResultCode.DIAMETER_INVALID_AVP_VALUE);
					}
				}

				// TODO: For Ro, support Service-Information AVP
				List<UnitReservation> reservations = new ArrayList<UnitReservation>();
				long resultCode = DiameterResultCode.DIAMETER_SUCCESS;

				MultipleServicesCreditControlAvp[] multipleServicesCreditControlAvps = ccr.getMultipleServicesCreditControls();
				for (MultipleServicesCreditControlAvp mscc : multipleServicesCreditControlAvps) {
					long requestedUnits = mscc.getRequestedServiceUnit().getCreditControlTotalOctets(); // FIXME: No diff between IN/OUT, from docs it seems

					long[] serviceIds = mscc.getServiceIdentifiers();

					//if its UPDATE, lets first update data 
					long usedUnitsCount = 0;
					if (ccr.getCcRequestType() == CcRequestType.UPDATE_REQUEST) {
						//update used units.
						UsedServiceUnitAvp[] usedUnits = mscc.getUsedServiceUnits();

						for (UsedServiceUnitAvp usedUnit : usedUnits) {
							usedUnitsCount += usedUnit.getCreditControlTotalOctets();
						}
						accountBalanceManagement.updateRequest(sessionId, endUserId, requestedUnits, usedUnitsCount, (int)ccr.getCcRequestNumber()); //...
						storedCCR = ccr;
						storedEndUserId = endUserId;
						storedRequestedUnits = requestedUnits;
						storedServiceIds = serviceIds;
						storedReservations = new ArrayList<UnitReservation>();
						return; // we'll continue @ resumeOnCreditControlRequest(..)
					}
					else {
						// Initial Request
						accountBalanceManagement.initialRequest(sessionId, endUserId, requestedUnits/*, tgppSgsnMccMnc, calledStationId, ccr.getDestinationHost().toString()*/);
						storedCCR = ccr;
						storedEndUserId = endUserId;
						storedRequestedUnits = requestedUnits;
						storedServiceIds = serviceIds;
						storedReservations = new ArrayList<UnitReservation>();
						return; // we'll continue @ resumeOnCreditControlRequest(..)
					}
				}

				if (reservations.size() > 0) {
					cca = createCCA(ccServerActivity, ccr, reservations, resultCode);
				}
				else {
					cca = createCCA(ccServerActivity, ccr, null, DiameterResultCode.DIAMETER_MISSING_AVP);
				}

				ccServerActivity.sendRoCreditControlAnswer(cca);
			}
			catch (Exception e) {
				tracer.severe("[xx] SID<" + ccr.getSessionId() + "> Failure processing Credit-Control-Request [" + (ccr.getCcRequestType() == CcRequestType.INITIAL_REQUEST ? "INITIAL" : "UPDATE") + "]", e);
			}
			break;
			// TERMINATION_REQUEST 3
		case TERMINATION_REQUEST:
			try {
				if (tracer.isInfoEnabled()) {
					tracer.info("[>>] SID<" + ccr.getSessionId() + "> '" + endUserId + "' requested service termination for '" + serviceContextId + "'.");
				}
				accountBalanceManagement = getAccountManager();

				for (MultipleServicesCreditControlAvp mscc : ccr.getMultipleServicesCreditControls()) {
					RequestedServiceUnitAvp rsu = mscc.getRequestedServiceUnit();
					long requestedUnits = rsu != null ? rsu.getCreditControlTotalOctets() : 0; // FIXME: No diff between IN/OUT, from docs it seems

					long[] serviceIds = mscc.getServiceIdentifiers();

					if (tracer.isInfoEnabled()) {
						tracer.info("[>>] SID<" + ccr.getSessionId() + "> '" + endUserId + "' requested " + requestedUnits + " octets for '" + Arrays.toString(serviceIds) + "'.");
					}

					// TODO: update used units.
					// UsedServiceUnitAvp[] usedUnits = mscc.getUsedServiceUnits();
					// 
					// for (UsedServiceUnitAvp usedUnit:usedUnits) {
					//   unitMonitor.updateUsed(serviceIds,usedUnit.getCreditControlTotalOctets());
					// }

					UsedServiceUnitAvp[] usedUnits = mscc.getUsedServiceUnits();
					long usedUnitsCount = 0;
					for (UsedServiceUnitAvp usedUnit : usedUnits) {

						usedUnitsCount += usedUnit.getCreditControlTotalOctets();
					}
					accountBalanceManagement.terminateRequest(sessionId, endUserId, 0L, usedUnitsCount, (int)ccr.getCcRequestNumber());
					storedCCR = ccr;
					storedEndUserId = endUserId;
					storedRequestedUnits = requestedUnits;
					storedServiceIds = serviceIds;
					storedReservations = new ArrayList<UnitReservation>();
					return;
				}

				// 8.7.  Cost-Information AVP
				// 
				// The Cost-Information AVP (AVP Code 423) is of type Grouped, and it is
				// used to return the cost information of a service, which the credit-
				// control client can transfer transparently to the end user.  The
				// included Unit-Value AVP contains the cost estimate (always type of
				// money) of the service, in the case of price enquiry, or the
				// accumulated cost estimation, in the case of credit-control session.
				// 
				// The Currency-Code specifies in which currency the cost was given.
				// The Cost-Unit specifies the unit when the service cost is a cost per
				// unit (e.g., cost for the service is $1 per minute).
				// 
				// When the Requested-Action AVP with value PRICE_ENQUIRY is included in
				// the Credit-Control-Request command, the Cost-Information AVP sent in
				// the succeeding Credit-Control-Answer command contains the cost
				// estimation of the requested service, without any reservation being
				// made.
				// 
				// The Cost-Information AVP included in the Credit-Control-Answer
				// command with the CC-Request-Type set to UPDATE_REQUEST contains the
				// accumulated cost estimation for the session, without taking any
				// credit reservation into account.
				// 
				// The Cost-Information AVP included in the Credit-Control-Answer
				// command with the CC-Request-Type set to EVENT_REQUEST or
				// TERMINATION_REQUEST contains the estimated total cost for the
				// requested service.
				// 
				// It is defined as follows (per the grouped-avp-def of
				// RFC 3588 [DIAMBASE]):
				// 
				//           Cost-Information ::= < AVP Header: 423 >
				//                                { Unit-Value }
				//                                { Currency-Code }
				//                                [ Cost-Unit ]

				// 7.2.133 Remaining-Balance AVP
				//
				// The Remaining-Balance AVP (AVPcode 2021) is of type Grouped and 
				// provides information about the remaining account balance of the 
				// subscriber.
				//
				// It has the following ABNF grammar:
				//      Remaining-Balance :: =  < AVP Header: 2021 >
				//                              { Unit-Value }
				//                              { Currency-Code }

				// We use no money notion ... maybe later. 
				// AvpSet costInformation = ccaAvps.addGroupedAvp(423);
				//always 2001 - since "4) The default action for failed operations should be to terminate the data session" - its terminated, we cant do much here...
				cca = createCCA(ccServerActivity, ccr, null, DiameterResultCode.DIAMETER_SUCCESS); 
				ccServerActivity.sendRoCreditControlAnswer(cca);
			}
			catch (Exception e) {
				tracer.severe("[xx] SID<" + ccr.getSessionId() + "> Failure processing Credit-Control-Request [TERMINATION]", e);
			}
			break;
			// EVENT_REQUEST 4
		case EVENT_REQUEST:
			if (tracer.isInfoEnabled()) {
				tracer.info("[<<] SID<" + ccr.getSessionId() + "> Received Credit-Control-Request [EVENT]");
			}
			aci.detach(this.getSbbContext().getSbbLocalObject());
			break;
		default:
			break;
		}

		// dump user status
		accountBalanceManagement.dump(endUserId);
	}

	public void onTimerEvent(TimerEvent timer, ActivityContextInterface aci) {
		// detach from this activity, we don't want to handle any other event on it
		aci.detach(this.sbbContextExt.getSbbLocalObject());
		((RoServerSessionActivity)aci.getActivity()).endActivity();
	}

	/**
	 * @param ccServerActivity
	 * @param request
	 * @param reservations
	 * @param resultCode
	 * @return
	 */
	private RoCreditControlAnswer createCCA(RoServerSessionActivity ccServerActivity, RoCreditControlRequest request, List<UnitReservation> reservations, long resultCode) {
		RoCreditControlAnswer answer = ccServerActivity.createRoCreditControlAnswer();

		// <Credit-Control-Answer> ::= < Diameter Header: 272, PXY >
		//  < Session-Id >
		//  { Result-Code }
		answer.setResultCode(resultCode);
		//  { Origin-Host }
		//  { Origin-Realm }
		//  { Auth-Application-Id }

		//  { CC-Request-Type }
		// Using the same as the one present in request
		answer.setCcRequestType(request.getCcRequestType());

		//  { CC-Request-Number }
		// Using the same as the one present in request
		answer.setCcRequestNumber(request.getCcRequestNumber());

		//  [ User-Name ]
		//  [ CC-Session-Failover ]
		//  [ CC-Sub-Session-Id ]
		//  [ Acct-Multi-Session-Id ]
		//  [ Origin-State-Id ]
		//  [ Event-Timestamp ]

		//  [ Granted-Service-Unit ]
		//
		// 8.17.  Granted-Service-Unit AVP
		//
		// Granted-Service-Unit AVP (AVP Code 431) is of type Grouped and
		// contains the amount of units that the Diameter credit-control client
		// can provide to the end user until the service must be released or the
		// new Credit-Control-Request must be sent.  A client is not required to
		// implement all the unit types, and it must treat unknown or
		// unsupported unit types in the answer message as an incorrect CCA
		// answer.  In this case, the client MUST terminate the credit-control
		// session and indicate in the Termination-Cause AVP reason
		// DIAMETER_BAD_ANSWER.
		//
		// The Granted-Service-Unit AVP is defined as follows (per the grouped-
		// avp-def of RFC 3588 [DIAMBASE]):
		//
		// Granted-Service-Unit ::= < AVP Header: 431 >
		//                          [ Tariff-Time-Change ]
		//                          [ CC-Time ]
		//                          [ CC-Money ]
		//                          [ CC-Total-Octets ]
		//                          [ CC-Input-Octets ]
		//                          [ CC-Output-Octets ]
		//                          [ CC-Service-Specific-Units ]
		//                         *[ AVP ]
		if (reservations != null && reservations.size() > 0) {
			MultipleServicesCreditControlAvp[] reqMSCCs = request.getMultipleServicesCreditControls();
			List<MultipleServicesCreditControlAvp> ansMSCCs = new ArrayList<MultipleServicesCreditControlAvp>();
			for (int index = 0 ; index < reservations.size(); index++) {
				MultipleServicesCreditControlAvp reqMSCC = reqMSCCs[index];
				MultipleServicesCreditControlAvp ansMscc = avpFactory.createMultipleServicesCreditControl();
				ansMscc.setRatingGroup(reqMSCC.getRatingGroup());
				ansMscc.setServiceIdentifiers(reqMSCC.getServiceIdentifiers());

				UnitReservation unitReservation = reservations.get(index);
				if (unitReservation.isSuccess()) {
					GrantedServiceUnitAvp gsu = avpFactory.createGrantedServiceUnit();
					gsu.setCreditControlTotalOctets(unitReservation.getUnits());
					ansMscc.setGrantedServiceUnit(gsu);
					ansMscc.setResultCode(DiameterResultCode.DIAMETER_SUCCESS);
				}
				else {
					ansMscc.setResultCode(getResultCode(unitReservation.getErrorCodeType()));
				}
				ansMSCCs.add(ansMscc);
				ansMscc.setValidityTime(DEFAULT_VALIDITY_TIME);
			}
			answer.setMultipleServicesCreditControls(ansMSCCs.toArray(new MultipleServicesCreditControlAvp[ansMSCCs.size()]));
		}

		// *[ Multiple-Services-Credit-Control ]
		//  [ Cost-Information]
		//  [ Final-Unit-Indication ]
		//  [ Check-Balance-Result ]
		//  [ Credit-Control-Failure-Handling ]
		//  [ Direct-Debiting-Failure-Handling ]
		//  [ Validity-Time]
		//Ro does not use message level VT
		// *[ Redirect-Host]
		//  [ Redirect-Host-Usage ]
		//  [ Redirect-Max-Cache-Time ]
		// *[ Proxy-Info ]
		// *[ Route-Record ]
		// *[ Failed-AVP ]
		// *[ AVP ]

		if (tracer.isInfoEnabled()) {
			tracer.info("[>>] SID<" + request.getSessionId() + "> Created Credit-Control-Answer with Result-Code = " + answer.getResultCode() + ".");
			if (tracer.isFineEnabled()) {
				tracer.fine(answer.toString());
			}
		}

		return answer;
	}

	// TODO: make it CMPs
	private RoCreditControlRequest storedCCR;
	private String storedEndUserId;
	private long storedRequestedUnits;
	private long[] storedServiceIds;
	private ArrayList<UnitReservation> storedReservations = new ArrayList<UnitReservation>();
	
	@Override
	public void resumeOnCreditControlRequest(UnitReservation ur) {
		if (tracer.isInfoEnabled()) {
			tracer.info("[<<] SID<" + storedCCR.getSessionId() + "> Resuming Handling of Credit-Control-Request [" + storedCCR.getCcRequestType().toString() + "]");
		}

		storedReservations.add(ur);
		long resultCode = DiameterResultCode.DIAMETER_SUCCESS;
		if (ur.isSuccess()) {
			if (tracer.isInfoEnabled()) {
				tracer.info("[>>] SID<" + storedCCR.getSessionId() + "> '" + storedEndUserId + "' GRANTED " + ur.getUnits() + " octets for '" + Arrays.toString(storedServiceIds) + "'.");
			}
		}
		else {
			if (tracer.isInfoEnabled()) {
				tracer.info("[>>] SID<" + storedCCR.getSessionId() + "> '" + storedEndUserId + "' DENIED " + storedRequestedUnits + " octets for '" + Arrays.toString(storedServiceIds) + "'.");
			}
			// FIXME: what is this ? why ?
			// if (CcRequestType.UPDATE_REQUEST == storedCCR.getCcRequestType()) {
			//	 accountBalanceManagement.updateRequest(ur.getSessionId(), storedEndUserId, 0L, usedUnitsCount , (int)ccr.getCcRequestNumber());
			// }
			resultCode = getResultCode(ur.getErrorCodeType());
		}
		if(storedCCR.getMultipleServicesCreditControls().length == storedReservations.size()) {
			RoServerSessionActivity activity = getServerSessionActivityToReply();
			RoCreditControlAnswer cca = storedReservations.size() > 0 ? createCCA(activity, storedCCR, storedReservations, resultCode) : createCCA(activity, storedCCR, null, DiameterResultCode.DIAMETER_MISSING_AVP);
			try {
				activity.sendRoCreditControlAnswer(cca);
			}
			catch (IOException e) {
				tracer.severe("[xx] Unable to send Credit-Control-Answer.", e);
			}
		}
	}
	
	@Override
	public void updateAccountDataResult(boolean success) {
		if (tracer.isInfoEnabled()) {
			tracer.info("[><] Update User Account Data " + (success ? "completed wit success." : "failed") + ".");
		}
	}

	private RoServerSessionActivity getServerSessionActivityToReply() {
		ActivityContextInterface[] acis = this.sbbContextExt.getActivities();
		Object activity = null;
		for (ActivityContextInterface aci : acis) {
			activity = aci.getActivity();
			if (activity instanceof RoServerSessionActivity) {
				// detach to not handle the activity end
				aci.detach(sbbContextExt.getSbbLocalObject());
				return (RoServerSessionActivity) activity;
			}
		}
		return null;
	}

}