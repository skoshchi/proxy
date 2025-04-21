package io.skoshchi.yaml;

import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.lra.annotation.ws.rs.LRA;

import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;

public class LRASettings  {
private LRA.Type type = LRA.Type.REQUIRED;
    private long timeLimit = 0;
    private ChronoUnit timeUnit = ChronoUnit.SECONDS;
    private boolean end = true;
    private List<Response.Status.Family> cancelOnFamily = Arrays.asList(
            Response.Status.Family.CLIENT_ERROR,
            Response.Status.Family.SERVER_ERROR
    );
    private List<Response.Status> cancelOn;

    public LRASettings () {
    }

    public LRA.Type getType() {
        return type;
    }

    public void setType(LRA.Type type) {
        this.type = type;
    }

    public long getTimeLimit() {
        return timeLimit;
    }

    public void setTimeLimit(long timeLimit) {
        this.timeLimit = timeLimit;
    }

    public ChronoUnit getTimeUnit() {
        return timeUnit;
    }

    public void setTimeUnit(ChronoUnit timeUnit) {
        this.timeUnit = timeUnit;
    }

    public boolean isEnd() {
        return end;
    }

    public void setEnd(boolean end) {
        this.end = end;
    }

    public List<Response.Status.Family> getCancelOnFamily() {
        return cancelOnFamily;
    }

    public void setCancelOnFamily(List<Response.Status.Family> cancelOnFamily) {
        this.cancelOnFamily = cancelOnFamily;
    }

    public List<Response.Status> getCancelOn() {
        return cancelOn;
    }

    public void setCancelOn(List<Response.Status> cancelOn) {
        this.cancelOn = cancelOn;
    }

    @Override
    public String toString() {
        return "LRASettings {" +
                "type=" + type +
                ", timeLimit=" + timeLimit +
                ", timeUnit=" + timeUnit +
                ", end=" + end +
                ", cancelOnFamily=" + cancelOnFamily +
                ", cancelOn=" + cancelOn +
                '}';
    }
}
