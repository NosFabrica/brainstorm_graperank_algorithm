package com.nosfabrica.graperank.stream;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.nosfabrica.graperank.grape.GrapeRankResult;

public class MessageQueueReturnValue {

    @JsonProperty("private_id")
    private int privateId;

    private GrapeRankResult result;

    public MessageQueueReturnValue(GrapeRankResult result, int privateId) {
        this.privateId = privateId;
        this.result = result;
    }

    public int getPrivateId() {
        return privateId;
    }

    public void setPrivateId(int privateId) {
        this.privateId = privateId;
    }

    public GrapeRankResult getResult() {
        return result;
    }

    public void setResult(GrapeRankResult result) {
        this.result = result;
    }
}
