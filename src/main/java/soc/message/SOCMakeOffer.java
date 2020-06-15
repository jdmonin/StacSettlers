/**
 * Java Settlers - An online multiplayer version of the game Settlers of Catan
 * Copyright (C) 2003  Robert S. Thomas
 * Portions of this file Copyright (C) 2009,2010 Jeremy D Monin <jeremy@nand.net>
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
 * The author of this program can be reached at thomas@infolab.northwestern.edu
 **/
package soc.message;

import soc.game.SOCResourceConstants;
import soc.game.SOCResourceSet;
import soc.game.SOCTradeOffer;

import java.util.StringTokenizer;


/**
 * This message means that a player wants to trade with other players
 *
 * @author Robert S. Thomas
 */
public class SOCMakeOffer extends SOCMessage
    implements SOCMessageForGame
{
    /**
     * Name of game
     */
    private String game;

    /**
     * The offer being made
     */
    private SOCTradeOffer offer;

    /**
     * Create a MakeOffer message.
     *
     * @param ga   the name of the game
     * @param of   the offer being made
     */
    public SOCMakeOffer(String ga, SOCTradeOffer of)
    {
        messageType = MAKEOFFER;
        game = ga;
        offer = of;
    }

    /**
     * @return the name of the game
     */
    public String getGame()
    {
        return game;
    }

    /**
     * @return the offer being made
     */
    public SOCTradeOffer getOffer()
    {
        return offer;
    }

    /**
     * @return the command string
     */
    public String toCmd()
    {
        return toCmd(game, offer);
    }

    /**
     * @return the command string
     *
     * @param ga  the name of the game
     * @param of   the offer being made
     */
    public static String toCmd(String ga, SOCTradeOffer of)
    {
        String cmd = MAKEOFFER + sep + ga;
        cmd += (sep2 + of.getFrom());

        boolean[] to = of.getTo();

        for (int i = 0; i < to.length; i++)  // length should be == game.maxPlayers
        {
            cmd += (sep2 + to[i]);
        }

        SOCResourceSet give = of.getGiveSet();

        for (int i = SOCResourceConstants.CLAY; i <= SOCResourceConstants.WOOD;
                i++)
        {
            cmd += (sep2 + give.getAmount(i));
        }

        SOCResourceSet get = of.getGetSet();

        for (int i = SOCResourceConstants.CLAY; i <= SOCResourceConstants.WOOD;
                i++)
        {
            cmd += (sep2 + get.getAmount(i));
        }

        return cmd;
    }

    /**
     * Parse the command String into a MakeOffer message
     *
     * @param s   the String to parse
     * @return    a MakeOffer message, or null of the data is garbled
     */
    public static SOCMakeOffer parseDataStr(String s)
    {
        String ga; // the game name
        int from; // the number of the offering player
        boolean[] to; // the players to which this trade is offered
        SOCResourceSet give; // the set of resources being asked for 
        SOCResourceSet get; // the set of resources that the offerer wants in exchange

        give = new SOCResourceSet();
        get = new SOCResourceSet();

        StringTokenizer st = new StringTokenizer(s, sep2);

        try
        {
            ga = st.nextToken();
            from = Integer.parseInt(st.nextToken());
            final int numPlayerTokens = st.countTokens() - (2 * 5);  // Should be == game.maxPlayers
            to = new boolean[numPlayerTokens];

            for (int i = 0; i < numPlayerTokens; i++)
            {
                to[i] = (Boolean.valueOf(st.nextToken())).booleanValue();
            }

            /**
             * Note: this only works if SOCResourceConstants.CLAY == 1
             */
            for (int i = 1; i <= SOCResourceConstants.WOOD; i++)
            {
                give.setAmount(Integer.parseInt(st.nextToken()), i);
            }

            for (int i = 1; i <= SOCResourceConstants.WOOD; i++)
            {
                get.setAmount(Integer.parseInt(st.nextToken()), i);
            }
        }
        catch (Exception e)
        {
            return null;
        }

        return new SOCMakeOffer(ga, new SOCTradeOffer(ga, from, to, give, get));
    }
    
    // Special handling:  
    // 1) There is a bizarre "offer=game=gameName" in the second position
    // 2) Give and get are specified as give=clay=x|ore=y...
    //  When we strip attrip names, we get a meaningless second entry, which we can skip, and due to manner of parsing, clay is inserted before give and get sets.
    public static String stripAttribNames(String message) {
    	// Strip the give= and get= from the message, then do the normal strip, then strip index 1 (could do more efficiently, but a previous incorrect implementation left
    	//  this same stuff here anyway, so use it.
    	message = message.replace("give=", "");
    	message = message.replace("get=", "");
    	// strip with leading delim (hardcode here for now)
    	message = message.replaceAll("\\|unknown=0", "");
    	String s = SOCMessage.stripAttribNames(message);
    	String[] pieces = s.split(SOCMessage.sep2);
    	
    	StringBuffer ret = new StringBuffer();
    	int[] skipIds = new int[]{1, -1};  // Append a -1 at the end so we don't have to worry about running off the end    	
    	int si = 0; // Which index of skipIds are we currently looking for?
    	for (int i=0; i<pieces.length; i++) {
    		if (skipIds[si]==i) {
    			// skip, but increment si
    			si++;
    		}
    		else {
    			ret.append(pieces[i]).append(SOCMessage.sep2);
    		}
    	}    	
    	
    	// trim the last separator - it interferes with the parse, which dynamically determines number of players based on number of tokens.
    	return ret.substring(0, ret.length() -1);
    }

    /**
     * @return a human readable form of the message
     */
    public String toString()
    {
        return "SOCMakeOffer:game=" + game + "|offer=" + offer;
    }
}
