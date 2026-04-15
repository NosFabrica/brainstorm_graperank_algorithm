package com.nosfabrica.graperank.stream;

//TIP To <b>Run</b> code, press <shortcut actionId="Run"/> or
// click the <icon src="AllIcons.Actions.Execute"/> icon in the gutter.
import com.nosfabrica.graperank.db.Neo4jHelper;
import com.nosfabrica.graperank.grape.GrapeRankAlgorithm;
import com.nosfabrica.graperank.grape.GrapeRankResult;

public class MainTest {

    public static void main(String[] args) {
        String observer = "f18571e72c93044670953ee53f03e6a500a6d97689b6ffcfe1cdcccf9da08ff4";

        GrapeRankAlgorithm helper = new GrapeRankAlgorithm(
            // TODO: Mock the DB
            new Neo4jHelper("neo4j://host.docker.internal:7687","neo4j","password")
        );

        GrapeRankResult result = helper.graperankAllSteps(observer);

        System.out.println("Success: " + result.isSuccess());
        System.out.println("Duration (seconds): " + result.getDurationSeconds());

    }
}