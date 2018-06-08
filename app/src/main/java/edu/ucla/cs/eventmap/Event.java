package edu.ucla.cs.eventmap;

public class Event {
    public String owner;
    public String username;
    public String name;
    public long time;
    public long duration;
    public double radius;
    public double lat;
    public double lng;
    public int hash;

    public Event() {
        hash = 0;
    }

    public Event(String owner, String username, String name, long time, long duration, double radius, double lat, double lng, int hash) {
        this.owner = owner;
        this.username = username;
        this.name = name;
        this.time = time;
        this.duration = duration;
        this.radius = radius;
        this.lat = lat;
        this.lng = lng;
        this.hash = hash;
    }

    public Event(int hash) {
        this.hash = hash;
    }

    @Override
    public boolean equals(Object v) {
        boolean equal = false;
        if (v instanceof Event) {
            if (this.hash == ((Event) v).hash) {
                equal = true;
            }
        }
        return equal;
    }
}