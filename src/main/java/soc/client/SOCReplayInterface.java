package soc.client;

import soc.client.SOCPlayerInterface.ClientBridge;
import soc.game.SOCGame;

/**
 * Interface for the Replay client.  Very similar to the PlayerInterface, but uses a {@link SOCReplayPanel} for
 *  playback control instead of the Building Panel (bottom center). 
 *<P>
 * The game text display used by {@link SOCPlayerInterface#print(String)}
 * is moved to the right side of the window to give it as much height as possible.
 * 
 * @author kho30
 *
 */
public class SOCReplayInterface extends SOCPlayerInterface {

	protected SOCReplayPanel replayPanel;
	
	protected SOCReplayClient rcl;
	
	public SOCReplayInterface(String title, SOCReplayClient cl, SOCGame ga) {
		super(title, cl.getMainDisplay(), ga, null, null);
		rcl = cl;
	}

	@Override
	protected ClientBridge createClientListenerBridge()
	{
		return new ReplayClientBridge(this);
	}
	
	protected void initUIElements(boolean firstCall)
    {
		super.initUIElements(firstCall);
		remove(buildingPanel);
		// Now add the new panel
		replayPanel = new SOCReplayPanel(this, client);
		replayPanel.setSize(buildingPanel.getWidth(), buildingPanel.getHeight());
		add(replayPanel);	
    }
	
	// When adding players, mark them as "playerIsClient" to show resources
	public void addPlayer(String n, int pn)
    {
		// Fake that we are this client to ensure it's added in the proper mode
		String oldName = rcl.getNickname(false);
		rcl.setNickname(n);
		super.addPlayer(n, pn);
		hands[pn].passiveMode();
		
		rcl.setNickname(oldName);
    }
	
	// Don't pop up the Dev Card dialogs
	public void showMonopolyDialog() {}
	public void showDiscoveryDialog() {}
	
	public void doLayout()
    {
		super.doLayout();
		
		// Set the replay panel to be the same size as the building panel was
		replayPanel.setBounds(buildingPanel.getBounds());
		repaint();
    }

    protected class ReplayClientBridge extends ClientBridge
    {
        public ReplayClientBridge(SOCReplayInterface pi)
        {
            super(pi);
        }

        @Override
        public void turnCountUpdated(final int forcedCount)
        {
            int count = (forcedCount == -1) ? game.getTurnCount() : forcedCount;
            ((SOCReplayInterface) pi).replayPanel.setTurnLabel(count);
        }

    }

}
