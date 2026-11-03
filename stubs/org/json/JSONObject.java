package org.json;
public class JSONObject {
    public JSONObject() {}
    public JSONObject(String s) throws Exception {}
    public JSONObject put(String k, Object v) throws Exception { return this; }
    public String getString(String k) throws Exception { return ""; }
    public double getDouble(String k) throws Exception { return 0; }
    public int getInt(String k) throws Exception { return 0; }
    public boolean has(String k) { return false; }
    public String optString(String k) { return ""; }
    public JSONArray getJSONArray(String k) throws Exception { return null; }
    public String toString() { return "{}"; }
}
