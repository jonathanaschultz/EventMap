package edu.ucla.cs.eventmap;

import java.io.Serializable;

public class Comment implements Serializable {
    public String event;
    public String owner;
    public String username;
    public String comment;
    public String time;
    public long hash; //Comment creation time in milliseconds is the hash, odds of collision are nigh impossible
    public int pin;

    public Comment() {
        hash = 0;
    }
    public Comment(String event, String owner, String username, String comment, String time, long hash, int pin) {
        this.event = event;
        this.owner = owner;
        this.username = username;
        this.comment = comment;
        this.time = time;
        this.hash = hash;
        this.pin = pin;
    }

    @Override
    public boolean equals(Object v) {
        boolean equal = false;
        if (v instanceof Comment) {
            if (this.hash == ((Comment) v).hash) {
                equal = true;
            }
        }
        return equal;
    }
}
