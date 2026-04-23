package com.nosfabrica.graperank.db;

import java.util.List;

public interface IGraphDB {
    List<String> getUsersConnectedToObserver(String observer, Integer hopsLimit);
}
