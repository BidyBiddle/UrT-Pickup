package de.gost0r.pickupbot.pickup;

import org.json.JSONObject;

public enum PlayerRank {
	DIAMOND("<:pickup_diamond:415516710708445185>"),
	PLATINUM("<:pickup_platinium:415517181674258432>"),
	GOLD("<:pickup_gold:415517181783179264>"),
	SILVER("<:pickup_silver:415517181481189387>"),
	BRONZE("<:pickup_bronze:415517181489709058>"),
	WOOD("<:pickup_wood:415517181137387520>");
	
	PlayerRank(String emoji) {
		this.emoji = emoji;
	}
	
	protected String emoji;
	public String getEmoji() {
		return emoji;
	}
	
	public JSONObject getEmojiJSON() {
		JSONObject emojiJSON = new JSONObject();
		String name = emoji.split(":")[1];
		String id = emoji.split(":")[2].replaceAll(">", "");
		emojiJSON.put("name", name);
		emojiJSON.put("id", id);
		
		return emojiJSON;
	}
}
