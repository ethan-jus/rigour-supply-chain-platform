package com.rigour.sales.domain;

import java.util.List;

/** Versioned CAM++ matching; similarity is not a probability or a visit verdict. */
public final class VoiceIdentity {
    private VoiceIdentity() {}
    public static boolean valid(List<Double> vector) {
        if(vector==null || vector.size()!=192 || vector.stream().anyMatch(v->v==null||!Double.isFinite(v)))return false;
        return Math.abs(vector.stream().mapToDouble(v->v*v).sum()-1)<0.05;
    }
    public static Integer match(List<Double> enrolled,List<List<Double>> speakers) {
        if(!valid(enrolled))return null;
        double best=-1,second=-1;Integer selected=null;
        for(int i=0;i<speakers.size();i++) {
            var vector=speakers.get(i);if(!valid(vector))continue;
            double score=0;for(int j=0;j<192;j++)score+=enrolled.get(j)*vector.get(j);
            if(score>best){second=best;best=score;selected=i;}else second=Math.max(second,score);
        }
        return best>=0.65 && best-second>=0.15?selected:null;
    }
}
