package com.tango.nihongo;

import java.util.Calendar;

/** Offline matching only: no model or network access. Lower scores rank first. */
final class LocalSearch {
    static String normalized(String text) { return LookupRules.normalize(text).replaceAll("[\\p{P}\\p{Z}\\s]+", ""); }
    static int score(String query,String... fields) {
        String q=normalized(query);if(q.isEmpty()) return 0;
        int best=Integer.MAX_VALUE;
        for(String field:fields) {
            String f=normalized(field);
            if(f.equals(q)) best=Math.min(best,0);
            else if(f.startsWith(q)) best=Math.min(best,1);
            else if(f.contains(q)) best=Math.min(best,2);
            else if(q.length()>=3) {
                int tolerance=q.length()>=6?2:1;
                for(String token:LookupRules.normalize(field).split("[\\p{P}\\p{Z}\\s]+")) {
                    if(Math.abs(token.length()-q.length())<=tolerance && distance(q,token)<=tolerance) best=Math.min(best,3);
                }
            }
        }
        return best;
    }
    private static int distance(String a,String b) {
        int[] row=new int[b.length()+1];for(int j=0;j<row.length;j++) row[j]=j;
        for(int i=1;i<=a.length();i++) { int diagonal=row[0];row[0]=i;for(int j=1;j<=b.length();j++) { int previous=row[j];row[j]=Math.min(Math.min(row[j]+1,row[j-1]+1),diagonal+(a.charAt(i-1)==b.charAt(j-1)?0:1));diagonal=previous; } }return row[b.length()];
    }
    static long periodStart(Calendar now,int days) {
        Calendar start=(Calendar)now.clone();start.set(Calendar.HOUR_OF_DAY,0);start.set(Calendar.MINUTE,0);start.set(Calendar.SECOND,0);start.set(Calendar.MILLISECOND,0);start.add(Calendar.DAY_OF_MONTH,1-days);return start.getTimeInMillis();
    }
}
