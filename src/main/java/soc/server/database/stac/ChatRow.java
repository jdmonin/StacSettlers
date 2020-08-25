package soc.server.database.stac;

import java.util.Arrays;

import soc.dialogue.StacTradeMessage;
import soc.message.SOCGameTextMsg;

/**
 * The row of a table containing the chat message
 * @author MG
 */
public class ChatRow {

	public static long counter = 0;
	
	private long id;
	private int current_state;
	private String sender;
	private String receivers;
	private String raw;
	private String message;
	
	public ChatRow(int current_state, StacTradeMessage msg) {
		id = counter;
		counter++;
		
		this.current_state = current_state;
		this.sender = msg.getSender();
		this.receivers = msg.getReceivers();
		this.raw = msg.toString();
		this.message = msg.getNLChatString();
	}
	
	public ChatRow(int current_state, SOCGameTextMsg msg) {
		id = counter;
		counter++;
		
		this.current_state = current_state;
		this.sender = "-1";
		this.receivers = "0,1,2,3";
		this.raw = msg.toString();
		this.message = msg.getText();
	}
	
	public ChatRow(long id, int current_state, StacTradeMessage msg) {
		this.id = id;
		
		this.current_state = current_state;
		this.sender = msg.getSender();
		this.receivers = msg.getReceivers();
		this.raw = msg.toString();
		this.message = msg.getNLChatString();
	}

	public long getId() {
		return id;
	}

	public void setId(long id) {
		this.id = id;
	}
	
	public int getCurrentState() {
		return this.current_state;
	}
	
	public String getSender() {
		return this.sender;
	}
	
	public String getReceivers() {
		return this.receivers;
	}
	
	public String getRaw() {
		return this.raw;
	}
	
	public String getMessage() {
		return this.message;
	}

	public String toString(){
		return "Row ID=" + id
			+ "|current_state=" + Integer.toString(this.current_state)
			+ "|sender=" + this.sender
			+ "|receivers=" + this.receivers
			+ "|raw=" + this.raw
			+ "|message=" + this.message;
	}
	
}
