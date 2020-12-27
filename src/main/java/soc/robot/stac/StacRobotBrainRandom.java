package soc.robot.stac;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.Set;

import soc.game.SOCGame;
import soc.game.SOCPlayer;
import soc.game.SOCPlayingPiece;
import soc.message.SOCChoosePlayer;
import soc.message.SOCDiceResult;
import soc.message.SOCGameStats;
import soc.message.SOCMoveRobber;
import soc.message.SOCPutPiece;
import soc.robot.SOCBuildPlanStack;
import soc.robot.SOCBuildingSpeedEstimate;
import soc.robot.SOCPlayerTracker;
import soc.robot.SOCPossibleCard;
import soc.robot.SOCPossibleCity;
import soc.robot.SOCPossibleRoad;
import soc.robot.SOCPossibleSettlement;
import soc.robot.SOCRobotBrain;
import soc.robot.SOCRobotClient;
import soc.robot.SOCRobotFactory;
import soc.robot.stac.flatmcts.FlatMctsRewards;
import soc.util.CappedQueue;
import soc.util.SOCRobotParameters;

/**
 * Robot Brain, DM and Factory wrapped into one to provide a random baseline.
 * Currently, only override planStuff, everything else uses JSettlers agent logic
 * Eventually, make it configurable what is randomized and what calls super.
 * Eg, could compare random planStuff/intelligent initial to intelligent planStuff/random initial 
 * 
 * @author KHO
 *
 * This class can be controlled by the flat MCTS implementation through a queue for playing the action selected at the tree level.
 * It can also be modified to play only a certain amount random if required, and the rest as a normal StacRobotBrain, hence it is used
 * during the simulations performed in the flat MCTS algorithm.
 *
 * @author MD
 *
 */
public class StacRobotBrainRandom extends StacRobotBrain {
    
	/**
	 * Flag for deciding if the build plan selection should be random or not
	 */
    private final boolean randomBuildPlan;
    /**
     * Flag for deciding if if the initial placement should be random or not
     */
    private final boolean randomInitial;
    /**
     * Percent of the time the robot plays random. If == 100, then robot is completely random. if == 0 it is a StacBrain.
     * To unset this decision based on percentages, set this to a negative value e.g. -1.
     */
    private final int randomPercentage;
    private CappedQueue controllingQueue;
    private FlatMctsRewards results;
    
    /**
     * Random build plan or not, set in {@link #randomOrNot()}.
     * In StacSettlers v1 this was {@code SOCRobotDMRandom.randomBuildPlan}.
     */
    private boolean randomBuildPlanOrNot;

    /**
     * Random build plan or not, set in {@link #randomOrNot()}.
     * In StacSettlers v1 this was {@code SOCRobotDMRandom.randomInitial}.
     */
    private boolean randomInitialOrNot;

    private static final Random RAND = new Random(new Date().getTime());

    /**
     * 
     * @param rc
     * @param params
     * @param ga
     * @param mq
     * @param randomBuildPlan should this robot play random
     * @param randomInitial should this robot place its first settlements random
     * @param randomPercentage the amount of random to play during either the initial phase or the normal gameplay depending on the randomInitial and randomBuildPlan being set
     * @param robotType
     * @param cq the queue used to control this robot if needed
     * @param results a reference to the results container for feedback the final outcome of a game
     */
    public StacRobotBrainRandom(SOCRobotClient rc, SOCRobotParameters params,
            SOCGame ga, CappedQueue mq, boolean randomBuildPlan, boolean randomInitial,int randomPercentage, StacRobotType robotType, CappedQueue cq, FlatMctsRewards results) {
        super(rc, params, ga, mq, false, robotType, new HashMap<String,ArrayList<String>>() );
        this.randomBuildPlan = randomBuildPlan;
        randomBuildPlanOrNot = randomBuildPlan;
        this.randomInitial = randomInitial;
        randomInitialOrNot = randomInitial;
        this.randomPercentage = randomPercentage;
        controllingQueue = cq; 
        this.results = results;
    }
    
    @Override
    protected void handleGAMESTATS(SOCGameStats message) {
    	if(results !=null){
//    		SOCGame game = this.getGame();
    		for(int i= 0; i < 4; i++){
//    			results.getEndVPs()[i] = game.getPlayer(i).getTotalVP(); (collected by the DBLogger for now)
    			playerTrackers[i].recalcWinGameETA();
    			results.getEndETWs()[i] = playerTrackers[i].getWinGameETA();
    		}
    	}
    	super.handleGAMESTATS(message);
    }
    
    /**
     * Passing the reference to the queue in MCTS
     * @param q
     */
    public void setQueue(CappedQueue q){
    	controllingQueue = q;
    }
    
    /**
     * 
     * @return the queue with the messages of the next actions to take;
     */
    public CappedQueue getQueue(){
    	return controllingQueue;
    }

    @Override
    protected void setStrategyFields() {
        super.setStrategyFields();
        openingBuildStrategy = new StacOBSRandom(game, ourPlayerData, this);
        robberStrategy = new StacRobberStrategyRandom(game, ourPlayerData, this, rand);
    }
    
    @Override
    protected StacRobotDM createDM() {
        return new SOCRobotDMRandom(this, buildingPlan);
    }
    
    @Override
    public void recreateDM(){
    	this.decisionMaker = createDM();
    }

    public FlatMctsRewards getResults() {
		return results;
	}

	public void setResults(FlatMctsRewards results) {
		this.results = results;
	}
	
    @Override
    protected void robberMoved(final int newHex){
    	super.robberMoved(newHex);
    	//update the immediate reward for the action of moving robber (if there is a choice of stealing resources, the result will get overwritten)
    	if(results != null && results.immediateRewardMoveRobber){
    		for(int i= 0; i< 4; i++){
    			SOCBuildingSpeedEstimate estimator = getEstimator();
    			estimator.recalculateEstimates(getGame().getPlayer(i).getNumbers(), newHex);
                int[] speeds = estimator.getEstimatesFromNothingFast(getGame().getPlayer(i).getPortFlags());
                int totalSpeed = 0;

                for (int j = SOCBuildingSpeedEstimate.MIN;
                        j < SOCBuildingSpeedEstimate.MAXPLUSONE; j++)
                {
                    totalSpeed += speeds[j];
                }
                
                results.getImmediateETBs()[i] = totalSpeed;
    		}
    		results.immediateRewardMoveRobber = false;//set a flag to avoid doing this for further robber moves.
    	}else if(results != null){
//    		results.countRss = false; //the robber was moved a second time in this game so might want to stop counting the blocked resources
    	}
    }
    
    @Override
    protected void handleDICERESULT(SOCDiceResult mes){
    	super.handleDICERESULT(mes);
    	if(mes.getResult() == 7){
    		//reset the past decision of robbing before making a new one
    		((StacRobberStrategyRandom) robberStrategy).robberLocation = -1;
    		((StacRobberStrategyRandom) robberStrategy).playerToRob = -1;
    	}
    	if(results != null && results.countRss && game.getBoard().getRobberHex() != -1){ //this is sometimes called once before the robber is moved, so we want to avoid that scenario
    		//instead of getting the total rss blocked, get rss blocked for each player
    		results.setRssBlocked(game.getResourcesBlocked(mes.getResult(), results.getRssBlocked())); 
    		//get the max number of rss that can be blocked per turn; Note: this will change if more sett/cities are placed next to the hex, so I think we will only want this gathered once at the beginning
    		results.setMaxTotalRssBlocked(game.getTotalPossibleBlockedRss()); 
    		//also get the number of resources that can possibly be blocked per turn for each player
    		results.setMaxRssBlocked(game.getPossibleBlockedRss());
    	}
    }

        /**
         * Choose whether we should play a random or planned next move. Based on the random percentage selected.
         */
        private void randomOrNot(){
        	if(randomPercentage > 0){ //if it is negative or 0, just use the initial flags for initial and buildPlan
	        	if(RAND.nextInt(100) < randomPercentage){
	        		randomBuildPlanOrNot = true;
	        		randomInitialOrNot = true;
	        	}else{
	        		randomBuildPlanOrNot = false;
	        		randomInitialOrNot = false;
	        	}
        	}
        }

	public static class SOCRobotDMRandom extends StacRobotDM {     

        /**
         * 
         * @param br
         */
        public SOCRobotDMRandom(StacRobotBrain br, SOCBuildPlanStack plan) {
            super(br, plan);
        }
        
        private static final Integer ROAD = Integer.valueOf(1);
        private static final Integer SETTLEMENT = Integer.valueOf(2);
        private static final Integer CITY = Integer.valueOf(3);
        private static final Integer DEV = Integer.valueOf(4);
        
        @Override
        public void planStuff() {
        	((StacRobotBrainRandom) brain).randomOrNot();
        	if (((StacRobotBrainRandom) brain).randomBuildPlanOrNot) {
                List<Integer> options = new ArrayList<Integer>(); // list of legal build types, using the build type constants 
                
                // Lists of legal build instances
                List<SOCPossibleCity> posCities = new ArrayList<SOCPossibleCity>();
                List<SOCPossibleSettlement> posSettlement = new ArrayList<SOCPossibleSettlement>();
                List<SOCPossibleRoad> posRoads = new ArrayList<SOCPossibleRoad>();
                
                SOCPlayerTracker ourPlayerTracker = brain.getOurPlayerTracker();
                // Determine what cities it is legal to build, if any
                if (player.getNumPieces(SOCPlayingPiece.CITY) > 0) {
                    Iterator posCitiesIter = ourPlayerTracker.getPossibleCities().values().iterator();
                    if (posCitiesIter.hasNext()) {
                        options.add(CITY);
                        while (posCitiesIter.hasNext()) {
                            SOCPossibleCity posCity = (SOCPossibleCity)posCitiesIter.next();
                            posCities.add(posCity);
                        }
                    }
                }
                // Legal settlements
                if (player.getNumPieces(SOCPlayingPiece.SETTLEMENT) > 0) {
                    Iterator posSetsIter = ourPlayerTracker.getPossibleSettlements().values().iterator();
                    if (posSetsIter.hasNext()) {
                        boolean isAdded = false;
                        while (posSetsIter.hasNext()) {
                            SOCPossibleSettlement posSet = (SOCPossibleSettlement)posSetsIter.next();
                            // Only consider settlements we can build immediately - ie no roads are necessary
                            if (posSet.getNecessaryRoads().size() == 0) {
                                if (isAdded == false) {
                                    options.add(SETTLEMENT);
                                    isAdded = true;
                                }     
                                posSettlement.add(posSet);
                            }
                        }
                    }
                }
                if (player.getGame().getNumDevCards() > 0) {
                    options.add(DEV);
                }
                // Legal roads
                if (player.getNumPieces(SOCPlayingPiece.ROAD) > 0) {            
                    Iterator posPiecesIter = ourPlayerTracker.getPossibleRoads().values().iterator();
                    if (posPiecesIter.hasNext()) {                    
                        boolean added = false;
                        while (posPiecesIter.hasNext()) {
                            SOCPossibleRoad posPiece = (SOCPossibleRoad)posPiecesIter.next();
                            // Only consider roads we can build immediately - ie no intermediate roads are necessary
                            if (posPiece.getNecessaryRoads().size() == 0) {                            
                                if (added == false) {
                                    options.add(ROAD);
                                    added = true;
                                }
                                posRoads.add((SOCPossibleRoad) posPiece);
                            }                        
                        }
                    }
                }
                
                if (options.size() == 0) {
                    System.err.println("Random out of options: " + brain.getGame().getName());
                    return;
                }
                
                // Choose an option from the list
                int i = RAND.nextInt(options.size());            
                Integer choice = options.get(i);
                
                // Choose an instance from the given type, if applicable, and set the build plan
                if (choice == CITY) {
                    i = RAND.nextInt(posCities.size());
                    buildingPlan.push(posCities.get(i));
                }
                else if (choice == SETTLEMENT) {
                    i = RAND.nextInt(posSettlement.size());
                    buildingPlan.push(posSettlement.get(i));
                }
                else if (choice == ROAD) {
                    i = RAND.nextInt(posRoads.size());
                    buildingPlan.push(posRoads.get(i));                        
                }
                else if (choice == DEV) {
                    buildingPlan.push(new SOCPossibleCard(player, 0));
                }            
            }
            else {
                super.planStuff();
            }
        }

    }

    /**
     * The {@link OpeningBuildStrategy} for {@link StacRobotBrainRandom}.
     * In STACSettlers v1 this code was part of {@code SOCRobotDMRandom}.
     * @since 2.4.50
     */
    protected static class StacOBSRandom extends StacOpeningBuildStrategy {

        public StacOBSRandom(SOCGame ga, SOCPlayer pl, StacRobotBrainRandom br) {
            super(ga, pl, br);
            firstSettlement = -1;
            secondSettlement = -1;
        }

        @Override
        public int planInitialSettlements() {
        	if(firstSettlement != -1){
//        		System.out.println("Remind the second settlement location at " + firstSettlement + " for player "+ brain.getPlayerNumber());
        		return firstSettlement;
        	}else if (! ((StacRobotBrainRandom) brain).getQueue().empty()) {
        		CappedQueue q = ((StacRobotBrainRandom) brain).getQueue();
        		SOCPutPiece msg = (SOCPutPiece) q.get();
//        		System.out.println("Player " + brain.getPlayerNumber() + " placing second Settlement at " + msg.getCoordinates()); //debug
        		firstSettlement = msg.getCoordinates();
        	}else{
//        		System.out.println("Normal planning initial settlement for player " + brain.getPlayerNumber());
	        	((StacRobotBrainRandom) brain).randomOrNot();
	        	if (((StacRobotBrainRandom) brain).randomInitialOrNot)
                            firstSettlement = getRandomLegalSettlement();
	        	else
                            firstSettlement = super.planInitialSettlements();
        	}

        	return firstSettlement;
        }

        @Override
        public int planSecondSettlement() {
        	if(secondSettlement != -1){
//        		System.out.println("Remind the second settlement location at " + secondSettlement + " for player "+ brain.getPlayerNumber());
        		return secondSettlement;
        	}else if (! ((StacRobotBrainRandom) brain).getQueue().empty()) {
        		CappedQueue q = ((StacRobotBrainRandom) brain).getQueue();
        		SOCPutPiece msg = (SOCPutPiece) q.get();
        		secondSettlement = msg.getCoordinates();
//        		System.out.println("Player " + brain.getPlayerNumber() + " placing second Settlement at " + msg.getCoordinates()); //debug
        	}else{
//	        	System.out.println("Normal planning second settlement for player " + brain.getPlayerNumber());
	        	((StacRobotBrainRandom) brain).randomOrNot();
	        	if (((StacRobotBrainRandom) brain).randomInitialOrNot)
                            secondSettlement = getRandomLegalSettlement();
	        	else
                            secondSettlement = super.planSecondSettlement();
        	}

        	return secondSettlement;
        }

        // TODO: Consider adding restrictions (ie don't build unless it's 3 hexes, or 2 hexes + port)
        private int getRandomLegalSettlement() {
            final Set<Integer> legalSettlements = ourPlayerData.getLegalSettlements();
            int irand = ((StacRobotBrainRandom) brain).RAND.nextInt(legalSettlements.size());
            Iterator<Integer> iter = legalSettlements.iterator();
            for (int i = 0; i < irand; ++i)
                iter.next();

            return iter.next().intValue();
        }

        @Override
        public int planInitRoad() {
            final int settlementNode = ourPlayerData.getLastSettlementCoord();
            ((StacRobotBrainRandom) brain).randomOrNot();
            if (((StacRobotBrainRandom) brain).randomInitialOrNot) {
                List<Integer> roads = brain.getGame().getBoard().getAdjacentEdgesToNode(settlementNode);
                boolean found = false;
                int road = -1;
                while (!found) {
                    int i = RAND.nextInt(roads.size());
                    road = ((Integer)roads.get(i)).intValue();
                    if (ourPlayerData.isLegalRoad(road)) {
                        found = true;
                    }
                }

                plannedRoadDestinationNode = -1;
                return road;
            }
            else {
                return super.planInitRoad();
            }
        }

    }

    /**
     * The {@link RobberStrategy} for {@link StacRobotBrainRandom}.
     * In STACSettlers v1 this code was part of {@code SOCRobotDMRandom}.
     * @since 2.4.50
     */
    protected static class StacRobberStrategyRandom extends StacRobberStrategy {

        /*a bug forces us to plan stuff twice as msgs are not processed by the server so it prevents us from doing the normal MCTS logic
         * as a result memorise the locations decided upon during the first search and the second time only resend the msg*/
        /** Robber location, set in {@link #getBestRobberHex()}, or -1 if not yet planned */
        protected int robberLocation = -1;

        /** Player to rob, set in {@link #chooseRobberVictim(boolean[], boolean)}, or -1 if not yet planned */
        protected int playerToRob = -1;

        public StacRobberStrategyRandom(SOCGame ga, SOCPlayer pl, StacRobotBrainRandom br, Random rand) {
            super(ga, pl, br, rand);
        }

        @Override
        public int getBestRobberHex() {
        	if(robberLocation != -1){
//        		System.out.println("Remind move robber to " + robberLocation + " for player "+ brain.getPlayerNumber()); //debug
        		return robberLocation;
        	}else if (! ((StacRobotBrainRandom)brain).getQueue().empty()) {//check if we are the robot controlled by MCTS
        		CappedQueue q = ((StacRobotBrainRandom)brain).getQueue();
        		SOCMoveRobber msg = (SOCMoveRobber) q.get();
        		robberLocation = msg.getCoordinates();
//        		System.out.println("Executing move robber to " + msg.getCoordinates()); //debug
        		return msg.getCoordinates();
        	}
            else {
                return super.getBestRobberHex(); //if playing by the old logic
            }
        }

        @Override
        public int chooseRobberVictim(final boolean[] isVictim, final boolean canChooseNone) {
        	if(playerToRob != -1){
//        		System.out.println("Remind choose player to rob: " + playerToRob + " for player "+ brain.getPlayerNumber()); //debug
        		return playerToRob;
        	}else if (! ((StacRobotBrainRandom)brain).getQueue().empty()) { //check if we are the robot controlled by MCTS
        		CappedQueue q = ((StacRobotBrainRandom)brain).getQueue();
        		SOCChoosePlayer msg = (SOCChoosePlayer) q.get();
        		playerToRob = msg.getChoice();
//        		System.out.println("Executing choose player: " + msg.getChoice()); //debug
        		return msg.getChoice();
        	}
            else {
                return super.chooseRobberVictim(isVictim, canChooseNone); //if playing by the old logic
            }
        }

    }

    public static class SOCRobotRandomFactory  implements SOCRobotFactory {
        private final boolean randomBuildPlan;
        private final boolean randomInitial;
        private final StacRobotType robotType;
        /**
         * percentage of time the robots created by this factory will play random, 100 = completely random.
         */
        private final int randomPercentage;
        private CappedQueue cq; 
        private FlatMctsRewards results;
        
        public SOCRobotRandomFactory(boolean randomBuildPlan, boolean randomInitial, int randomPercentage, StacRobotType robotType) {  
            this.randomBuildPlan = randomBuildPlan;
            this.randomInitial = randomInitial;
            this.robotType = robotType;
            this.randomPercentage = randomPercentage;
            this.cq = new CappedQueue();//an empty initialized queue to avoid nullpointer when checking if it is empty
            this.results = null;//usually null unless a reference is passed from MCTS; this will help avoiding executing some lines of code
        }
        /**
         * For sending a reference to the controlling queue 
         * @param q the same queue StacMCTS uses to place the actions at the tree level
         */
        public void setQueue(CappedQueue q){
        	cq = q;
        }
        
        /**
         * For passing the reference from MCTS
         * @param s the container created in the controlling MCTS
         */
        public void setStatsContainer(FlatMctsRewards s){
        	results = s;
        }

        public SOCRobotBrain getRobot(SOCRobotClient cl, SOCRobotParameters params, SOCGame ga,
                CappedQueue mq) {
            return new StacRobotBrainRandom(cl, params, ga, mq, randomBuildPlan, randomInitial, randomPercentage, robotType, cq, results);
        }

        public boolean isType(String type) {
            return robotType.isType(type);
        }

        public void setTypeFlag(String type, String param) {
            robotType.addType(type,  param);
        }

        public void setTypeFlag(String type) {
            robotType.addType(type);
        }
    }
}
