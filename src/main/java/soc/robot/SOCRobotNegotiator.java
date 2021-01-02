/**
 * Java Settlers - An online multiplayer version of the game Settlers of Catan
 * Copyright (C) 2003  Robert S. Thomas <thomas@infolab.northwestern.edu>
 * Portions of this file Copyright (C) 2009,2011-2013,2015,2017-2018,2020 Jeremy D Monin <jeremy@nand.net>
 * Portions of this file Copyright (C) 2012 Paul Bilnoski <paul@bilnoski.net>
 * Portions of this file Copyright (C) 2017-2018 Strategic Conversation (STAC Project) https://www.irit.fr/STAC/
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 3
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 * The maintainer of this program can be reached at jsettlers@nand.net
 **/
package soc.robot;


import soc.disableDebug.D; 

import soc.game.SOCGame;
import soc.game.SOCPlayer;
import soc.game.SOCResourceConstants;
import soc.game.SOCResourceSet;
import soc.game.SOCTradeOffer;
import soc.message.SOCMakeOffer;
import soc.message.SOCRejectOffer;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Vector;

import java.util.Random;


/**
 * Make and consider resource trade offers ({@link SOCTradeOffer}) with other players.
 *<P>
 * Chooses a response:
 *<UL>
 * <LI> {@link #IGNORE_OFFER}
 * <LI> {@link #REJECT_OFFER}
 * <LI> {@link #ACCEPT_OFFER}
 * <LI> {@link #COUNTER_OFFER}
 *</UL>
 *<P>
 * Moved the routines that make and
 * consider offers out of the robot
 * brain.
 *
 * Refactored to move much of the decision making into implementations.
 * TODO: Reactor more, eg "isSelling" functionality, which we will definitely
 * want to experiment with (deciding what constitutes unwillingness to trade
 * is a crucial aspect of trade strategy)
 *
 * @author Robert S. Thomas
 */
public abstract class SOCRobotNegotiator<BP extends SOCBuildPlan>
{
    // Use a randomizer to decide whether to make a partial offer, occasionally
    protected static final Random RANDOM = new Random();

    protected static final int WIN_GAME_CUTOFF = 25;

    /**
     * Response: Ignore an offer. Should be used only if we aren't
     * among the offer's recipients from {@link SOCTradeOffer#getTo()}.
     * If the offer is meant for us, the offering player is waiting for
     * our response and ignoring it will delay the game.
     * @since 2.0.00
     */
    public static final int IGNORE_OFFER = -1;

    /** Response: Reject an offer. */
    public static final int REJECT_OFFER = 0;

    /** Response: Accept an offer. */
    public static final int ACCEPT_OFFER = 1;

    /** Response: Plan and make a counter-offer if possible, otherwise reject. */
    public static final int COUNTER_OFFER = 2;

    public static final int COMPLETE_OFFER = 3;

    protected final SOCRobotBrain<?, ?, BP> brain;
    protected SOCGame game;

    /**
     * Player trackers, one per player number; vacant seats are null.
     * Same format as {@link SOCRobotBrain#getPlayerTrackers()}.
     * @see #ourPlayerTracker
     */
    protected SOCPlayerTracker[] playerTrackers;

    /** Player tracker for {@link #ourPlayerData}. */
    protected SOCPlayerTracker ourPlayerTracker;

    protected SOCPlayer ourPlayerData;

    /**
     * {@link #ourPlayerData}'s player number.
     * @since 2.0.00
     */
    protected int ourPlayerNumber;
       
    /**
     * constructor
     *
     * @param br  the robot brain
     */
    public SOCRobotNegotiator(SOCRobotBrain<?, ?, BP> br)
    {
        brain = br;
        playerTrackers = brain.getPlayerTrackers();
        ourPlayerTracker = brain.getOurPlayerTracker();
        ourPlayerData = brain.getOurPlayerData();
        ourPlayerNumber = ourPlayerData.getPlayerNumber();
        game = brain.getGame();

        resetTargetPieces();
    }

    /**
     * reset target pieces for all players
     */
    public abstract void resetTargetPieces();

    /**
     * set a target piece for a player
     *
     * @param pn  the player number
     * @param buildPlan  the current build plan
     */
    public abstract void setTargetPiece(int pn, BP buildPlan); //SOCPossiblePiece piece)

    /**
     * Forget all trade offers made.
     */
    public abstract void resetOffersMade();

    /**
     * Remembers an offer.
     *
     * @param offer  the offer
     */
    public abstract void addToOffersMade(SOCTradeOffer offer);

    /**
     * reset the isSellingResource array so that
     * if the player has the resource, then he is selling it
     */
    protected abstract void resetIsSelling();
    
    /**
     * mark a player as not selling a resource
     *
     * @param pn         the number of the player
     * @param rsrcType   the type of resource
     */
    protected abstract void markAsNotSelling(int pn, int rsrcType);

    /**
     * mark a player as willing to sell a resource
     *
     * @param pn         the number of the player
     * @param rsrcType   the type of resource
     */
    protected abstract void markAsSelling(int pn, int rsrcType);
    
    /**
     * Forget the trades we made.
     */
    public abstract void resetTradesMade();

    /***
     * Make an trade offer to another player, or decide to make no offer,
     * based on what we want to build and our player's current {@link SOCPlayer#getResources()}.
     *
     * @param buildPlan  our build plan, or {@code null}
     * @return the offer we want to make, or {@code null} for no offer
     * @see #getOfferToBank(BP, SOCResourceSet)
     */
    public abstract SOCTradeOffer makeOffer(BP buildPlan);

    /**
     * @return a counter offer or null
     *
     * @param originalOffer  the offer given to us
     */
    public abstract SOCTradeOffer makeCounterOffer(SOCTradeOffer originalOffer);

    /**
     * Decide what bank/port trade to request, if any,
     * based on which resources we want and {@code ourResources}.
     *<P>
     * Other forms of {@code getOfferToBank(..)} call this one;
     * this is the one to override if a third-party bot wants to
     * customize {@code getOfferToBank} behavior.
     *
     * @return the offer that we'll make to the bank/ports,
     *     or {@code null} if {@code ourResources} already contains all needed {@code targetResources}
     *     or {@code targetResources} is null or empty
     * @param buildPlan  our build plan, or {@code null} or empty
     * @param ourResources     the resources we have; not null
     */
    public abstract SOCTradeOffer getOfferToBank(BP buildPlan, SOCResourceSet ourResources);


    /**
     * consider an offer made by another player
     *
     * @param offer  the offer to consider
     * @param receiverNum  the player number of the receiver
     *
     * @return if we want to accept, reject, or make a counter offer
     *     ( {@link #ACCEPT_OFFER}, {@link #REJECT_OFFER}, or {@link #COUNTER_OFFER} )
     */
    public abstract int considerOffer(SOCTradeOffer offer, int receiverNum);

  
    /**
     * Determine whether to make a counter for a given partial offer
     *  Set the appropraite variables, return the status of the offer
     * @param offer
     * @return
     */
    protected abstract int handlePartialOffer(SOCTradeOffer offer);
    
    /**
     * reset the wantsAnotherOffer array to all false
     */
    public abstract void resetWantsAnotherOffer();
    
    /**
     * mark a player as not wanting another offer
     *
     * @param pn         the number of the player
     * @param rsrcType   the type of resource
     */
    public abstract void markAsNotWantingAnotherOffer(int pn, int rsrcType);
    
    /**
     * mark a player as wanting another offer
     *
     * @param pn         the number of the player
     * @param rsrcType   the type of resource
     */
    public abstract void markAsWantsAnotherOffer(int pn, int rsrcType);
    
    /**
     * @return true if the player is marked as wanting a better offer
     *
     * @param pn         the number of the player
     * @param rsrcType   the type of resource
     */
    public abstract boolean wantsAnotherOffer(int pn, int rsrcType);
    
    /**
     * @param offer the new best completed offer
     */
    public abstract void setBestCompletedOffer(SOCTradeOffer offer);
    
    /**
     * @return the best completed offer (either directly or from memory)
     */
    public abstract SOCTradeOffer getBestCompletedOffer();

///methods used by all implementations///
    /**
     * @return the offer that we'll make to the bank/ports
     *
     * @param  buildPlan  our build plan, or {@code null}
     * @see #makeOffer(BP)
     */
    public SOCTradeOffer getOfferToBank(BP buildPlan) //SOCResourceSet targetResources)
    {
        return getOfferToBank(buildPlan, ourPlayerData.getResources());
    }
    
    /**
     * Updates in order to point to the new/correct fields updated in the brain
     */
    public void update(){
        playerTrackers = brain.getPlayerTrackers();
        ourPlayerTracker = brain.getOurPlayerTracker();
        ourPlayerData = brain.getOurPlayerData();
        ourPlayerNumber = ourPlayerData.getPlayerNumber();
        game = brain.getGame();
    }

    /// logic recording isSelling or wantingAnotherOffer based on responses: Accept, Reject or no response ///

    /**
     * Marks what a player wants or is not selling based on the received offer.
     * @param offer the offer we have received
     */
    protected void recordResourcesFromOffer(SOCTradeOffer offer)
    {
        ///
        /// record that this player wants to sell me the stuff
        ///
        SOCResourceSet giveSet = offer.getGiveSet();

        for (int rsrcType = SOCResourceConstants.CLAY;
                rsrcType <= SOCResourceConstants.WOOD;
                rsrcType++)
        {
            if (giveSet.contains(rsrcType))
            {
                D.ebugPrintlnINFO("%%% player " + offer.getFrom() + " wants to sell " + rsrcType);
                markAsWantsAnotherOffer(offer.getFrom(), rsrcType);
            }
        }

        ///
        /// record that this player is not selling the resources 
        /// he is asking for
        ///
        SOCResourceSet getSet = offer.getGetSet();

        for (int rsrcType = SOCResourceConstants.CLAY;
                rsrcType <= SOCResourceConstants.WOOD;
                rsrcType++)
        {
            if (getSet.contains(rsrcType))
            {
                D.ebugPrintlnINFO("%%% player " + offer.getFrom() + " wants to buy " + rsrcType + " and therefore does not want to sell it");
                markAsNotSelling(offer.getFrom(), rsrcType);
            }
        }
    }
    
    /**
     * Marks what resources a player is not selling based on a reject to our offer
     *<P>
     * To do so for another player's offer, use {@link #recordResourcesFromRejectAlt(int)}.
     *
     * @param rejector the player number corresponding to the player who has rejected an offer
     */
    protected void recordResourcesFromReject(int rejector)
    {
        ///
        /// see if everyone has rejected our offer
        ///
    	if (brain.waitingForTradeResponse) { 
            // If this is false, it means the rejected trade was accepted by another player.
            //  Since it has been cleared from the data object, it unfortunately cannot be
            //  passed to the negotiator.
            //  TODO: Rework so that we have access to this?
            if (ourPlayerData.getCurrentOffer() != null) 
            {
                D.ebugPrintlnINFO("%%%%%%%%% REJECT OFFER %%%%%%%%%%%%%");
    
                ///
                /// record which player said no
                ///
                SOCResourceSet getSet = ourPlayerData.getCurrentOffer().getGetSet();
    
                for (int rsrcType = SOCResourceConstants.CLAY;
                        rsrcType <= SOCResourceConstants.WOOD;
                        rsrcType++)
                {
                    if (getSet.contains(rsrcType) && ! wantsAnotherOffer(rejector, rsrcType))
                        markAsNotSelling(rejector, rsrcType);
                }
            }       
        }
    }
    
    /**
     * Marks what resources a player is not selling based on a reject to other offers
     *<P>
     * To do so for our player's offer, use {@link #recordResourcesFromReject(int)}.
     *
     * @param rejector the player number corresponding to the player who has rejected an offer
     */
    protected void recordResourcesFromRejectAlt(int rejector)
    {
    	///
    	/// we also want to watch rejections of other players' offers
        ///
        D.ebugPrintlnINFO("%%%% ALT REJECT OFFER %%%%");

        for (int pn = 0; pn < game.maxPlayers; pn++)
        {
            SOCTradeOffer offer = game.getPlayer(pn).getCurrentOffer();

            if (offer != null)
            {
                boolean[] offeredTo = offer.getTo();

                if (offeredTo[rejector])
                {
                    //
                    // I think they were rejecting this offer
                    // mark them as not selling what was asked for
                    //
                    SOCResourceSet getSet = offer.getGetSet();

                    for (int rsrcType = SOCResourceConstants.CLAY;
                            rsrcType <= SOCResourceConstants.WOOD;
                            rsrcType++)
                    {
                    	if (getSet.contains(rsrcType) && ! wantsAnotherOffer(rejector, rsrcType))
                    		markAsNotSelling(rejector, rsrcType);
                    }
                }
            }
        }
    }
    
    /**
     * This is called when players haven't responded to our offer,
     * so we assume they are not selling and that they don't want anything else.
     * Marks the resources we offered as not selling and marks that the player doesn't want a different offer for that resource
     * @param ourCurrentOffer the offer we made and not received an answer to
     */
    protected void recordResourcesFromNoResponse(SOCTradeOffer ourCurrentOffer)
    {
    	boolean[] offeredTo = ourCurrentOffer.getTo();
        SOCResourceSet getSet = ourCurrentOffer.getGetSet();

        for (int rsrcType = SOCResourceConstants.CLAY;
                rsrcType <= SOCResourceConstants.WOOD;
                rsrcType++)
        {
            if (getSet.contains(rsrcType))
            {
                for (int pn = 0; pn < game.maxPlayers; pn++)
                {
                    if (offeredTo[pn])
                    {
                        markAsNotSelling(pn, rsrcType);
                        markAsNotWantingAnotherOffer(pn, rsrcType);
                    }
                }
            }
        }
    }

}
