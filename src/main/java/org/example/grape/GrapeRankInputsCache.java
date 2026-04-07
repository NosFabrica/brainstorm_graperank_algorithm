package org.example.grape;

import java.util.List;
import java.util.Map;
import java.util.Set;

public class GrapeRankInputsCache {

    private Map<String, List<GrapeRankInput>> inputs;
    private Set<String> raters;

    public GrapeRankInputsCache() {}

    public GrapeRankInputsCache(Map<String, List<GrapeRankInput>> inputs, Set<String> raters) {
        this.inputs = inputs;
        this.raters = raters;
    }

    public Map<String, List<GrapeRankInput>> getInputs() {
        return inputs;
    }

    public void setInputs(Map<String, List<GrapeRankInput>> inputs) {
        this.inputs = inputs;
    }

    public Set<String> getRaters() {
        return raters;
    }

    public void setRaters(Set<String> raters) {
        this.raters = raters;
    }
}
