package com.nosfabrica.graperank.db;

public class RelationshipInfo {
    private String source;
    private String relationship;
    private String target;

    public RelationshipInfo(String source, String relationship, String target) {
        this.source = source;
        this.relationship = relationship;
        this.target = target;
    }

    public String getSource() {
        return source;
    }

    public String getRelationship() {
        return relationship;
    }

    public String getTarget() {
        return target;
    }

    @Override
    public String toString() {
        return "RelationshipInfo{" +
                "source='" + source + '\'' +
                ", relationship='" + relationship + '\'' +
                ", target='" + target + '\'' +
                '}';
    }
}