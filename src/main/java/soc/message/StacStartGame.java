/**
 * Java Settlers - An online multiplayer version of the game Settlers of Catan
 * This file Copyright (C) 2020 Jeremy D Monin <jeremy@nand.net>
 * Portions of this file from SOCStartGame.java Copyright (C) 2017-2018 Strategic Conversation (STAC Project) https://www.irit.fr/STAC/
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
package soc.message;

import java.util.StringTokenizer;

import soc.game.SOCGame;


/**
 * This STAC-specific message means that a player wants to start the game with certain parameters.
 *<P>
 * Stac v1 instead added parameters to the v1.1.12 {@link SOCStartGame} which are incompatible with v2.
 *<P>
 * From client, this message means that a player wants to start the game;
 * from server, it means that a game has just started, leaving state {@code NEW}.
 * The server sends the game's new {@link SOCGameState} before sending {@code StacStartGame} or {@code SOCStartGame}.
 *<P>
 * In v2.0.00 and newer, from server this message optionally includes a {@link #getGameState()} field
 * instead of a separate {@link SOCGameState} message, since the state is part of the Start Game transition.
 *<P>
 * If a client joins a game in progress, it won't be sent a {@code StacStartGame} message,
 * only the game's current {@code SOCGameState} and other parts of the game's and
 * players' current status: See {@link SOCJoinGameAuth}.
 */
public class StacStartGame extends SOCMessage
    implements SOCMessageForGame
{
    private static final long serialVersionUID = 2450L;  // no structural changes since introduced in v2.4.50

    /**
     * Name of game
     */
    private String game;

     /**
     * flag for stopping the order of the robots being randomized.
     */
    private boolean noShuffle;

    /**
     * flag for loading a game.
     */
    private boolean load;
    
    /**
     * name of the folder containing the files with the saved game data.
     */
    private String folderName;
    
    /**
     * How many turns to run the simulations for.
     */
    private int noTurns;
    
    /**
     * player number to start the game or -1 if randomize
     */
    private int playerToStart;
    
    /**
     * a flag telling us if we want to start a new game on a saved board layout which should be found in saves/board/soc.game.SOCBoard.dat
     */
    private boolean loadBoard;
    
    /**
     * a flag for telling the server if the trading in this game will be done via the chat or the old trade interface
     */
    private boolean chatNegotiations;
    
    /**
     * a flag for telling the server if the game is fully observable
     */
    private boolean fullyObservable;
    
    /**
     * a flag for telling the server if drawing VP cards is an observable action.
     */
    private boolean observableVP;

    /**
     * The optional {@link SOCGame} State field, or 0.
     * See {@link #getGameState()} for details.
     * @since 2.0.00
     */
    private final int gameState;

    /**
     * Create a StartGame message.
     *
     * @param ga  the name of the game
     * @param ns flag for deciding whether to shuffle the robot's position or not
     * @param l flag for deciding whether to load or start a new game
     * @param fn pathname to the folder containing the save files e.g. "saves/robot"
     * @param t the number of turns this game is allowed to run for.
     * @param pts the player number(or board position) of the player to start the game
     * @param lb flag for deciding whether to create a new board or load a saved configuration
     * @param fo flag for deciding if the game is fully observable or has hidden information
     * @param ov flag for deciding if drawing vp cards is an observable action
     * @param gs  the new turn's optional Game State such as {@link SOCGame#ROLL_OR_CARD}, or 0.
     *     Ignored from client. Values &lt; 0 are out of range and ignored (treated as 0).
     *     Must not send {@code gs} to a client older than {@link SOCGameState#VERSION_FOR_GAME_STATE_AS_FIELD}.
     */
    public StacStartGame(final String ga, boolean ns, boolean l, String fn, int t, int pts, boolean lb, boolean cn, boolean fo, boolean ov, final int gs)
    {
        messageType = STACSTARTGAME;
        game = ga;
        noShuffle = ns;
        load = l;
        String name = fn;
        if(!(name.contains("@"))){
    		name = "@" + fn;
    	}
        folderName = name; //add a character to avoid nullpointer
        noTurns = t;
        playerToStart = pts;
        loadBoard = lb;
        chatNegotiations = cn;
        fullyObservable = fo;
        observableVP = ov;
        gameState = (gs > 0) ? gs : 0;
    }

    /**
     * @return observable vp flag
     */
    public boolean getObservableVPFlag(){
    	return observableVP;
    }
    
    /**
     * @return fully observable flag
     */
    public boolean getFullyObservableFlag(){
    	return fullyObservable;
    }
    
    /**
     * @return how are trades performed
     */
    public boolean getChatNegotiationsFlag(){
    	return chatNegotiations;
    }
    
    /**
     * @return the player number of the player to start the game or -1 if random
     */
    public int getStartingPlayer(){
    	return playerToStart;
     }

    /**
     * @return the name of the game
     */
    public String getGame()
    {
        return game;
    }

     /**
     * @return the load flag
     */
    public boolean getLoadFlag()
    {
        return load;
    }
    
    /**
     * @return the loadBoard flag
     */
    public boolean getLoadBoardFlag()
    {
        return loadBoard;
    }
    
    /**
     * @return the noShuffle flag
     */
    public boolean getShuffleFlag()
    {
        return noShuffle;
    }
    
    /**
     * @return the name of the folder containing the saved game files
     */
    public String getFolder()
    {
    	String name = folderName.replace("@", "");//get rid of the special character
        return name;
    }
	/**
	 * @return the number of turns until the game is stopped
	 */
    public int getTurnNo(){
		return noTurns;
	}

    /**
     * From server, get the the new turn's optional {@link SOCGame} State.
     * Ignored if sent from client. Must not be sent by server to clients older
     * than v2.0.00 ({@link SOCGameState#VERSION_FOR_GAME_STATE_AS_FIELD}) because they
     * won't parse it out and instead will treat state as part of the game name.
     * @return Game State, such as {@link SOCGame#ROLL_OR_CARD}, or 0
     * @since 2.0.00
     */
    public int getGameState()
    {
        return gameState;
    }

    /**
     * STACSTARTGAME sep game sep2 ns sep2 load sep2 ... [sep2 gameState]
     *
     * @return the command string
     */
    public String toCmd()
    {
        return toCmd(game, noShuffle, load, folderName, noTurns, playerToStart, loadBoard, chatNegotiations, fullyObservable, observableVP, gameState);
    }

    /**
     * STACSTARTGAME sep game sep2 ns sep2 load sep2 ... [sep2 gameState]
     *
     * @param ga  the name of the game
     * @param ns flag for deciding whether to shuffle the robot's position or not
     * @param l flag for deciding whether to load or start a new game
     * @param fn pathname to the folder containing the save files e.g. "saves/robot"
     * @param t the number of turns this game is allowed to run for.
     * @param pts the player number(or board position) of the player to start the game
     * @param lb flag for deciding whether to create a new board or load a saved configuration
     * @param fo flag for deciding if the game is fully observable or has hidden information
     * @param ov flag for deciding if drawing a vp card is an observable action
     * @param gs  the new turn's optional Game State such as {@link SOCGame#ROLL_OR_CARD}, or 0 to omit that field
     * @return the command string
     */
    public static String toCmd(final String ga, boolean ns, boolean l, String fn, int t, int pts, boolean lb, boolean cn, boolean fo, boolean ov, final int gs)
    {
        String name = fn;
        if(!(name.contains("@"))){ 
    		name = "@" + fn;
    	}
        return STACSTARTGAME + sep + ga + sep2 + ns + sep2 + l + sep2 + name + sep2 + t + sep2 + pts + sep2 + lb + sep2 + cn + sep2 + fo + sep2 + ov + ((gs > 0) ? sep2 + gs : "");
    }

    /**
     * Parse the command String into a StartGame message
     *
     * @param s   the String to parse
     * @return    a StartGame message, or null if the data is garbled
     */
    public static StacStartGame parseDataStr(String s)
    {
        String ga;   // the game name
        boolean ns;
        boolean l;
        String fn;
        int t;
        int pts;
        boolean lb;
        boolean cn;
        boolean fo;
        boolean ov;
        int gs = 0;  // the game state

        StringTokenizer st = new StringTokenizer(s, sep2);

        try
        {
            ga = st.nextToken();
            ns = Boolean.parseBoolean(st.nextToken());
            l = Boolean.parseBoolean(st.nextToken());
            fn = st.nextToken();
            t = Integer.parseInt(st.nextToken());
            pts = Integer.parseInt(st.nextToken());
            lb = Boolean.parseBoolean(st.nextToken());
            cn = Boolean.parseBoolean(st.nextToken());
            fo = Boolean.parseBoolean(st.nextToken());
            ov = Boolean.parseBoolean(st.nextToken());
            if (st.hasMoreTokens())
                gs = Integer.parseInt(st.nextToken());
        } catch (Exception e) {
            return null;
        }

        return new StacStartGame(ga, ns, l, fn, t, pts, lb, cn, fo, ov, gs);
    }

    /**
     * @return a human readable form of the message
     */
    public String toString()
    {
        return "StacStartGame:game=" + game + "|noShuffle=" + noShuffle + "|load=" + load + "|folderName=" + folderName + "|turns=" + noTurns + "|PlayerToStart=" + playerToStart + "|LoadBoard=" + loadBoard + "|ChatNegotiations=" + chatNegotiations + "|FullyObservable=" + fullyObservable + "|ObservableVp=" + observableVP + ((gameState != 0) ? "|gameState=" + gameState : "");
    }

}
