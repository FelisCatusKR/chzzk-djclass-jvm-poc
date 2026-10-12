package kr.felis.djclass.realtime;

import java.util.Map;

public record ChatMessage(String senderChannelId, String nickname, String content, Map<String, String> emojis) {}
