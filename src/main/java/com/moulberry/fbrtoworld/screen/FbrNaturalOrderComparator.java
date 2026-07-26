package com.moulberry.fbrtoworld.screen;

import java.util.Comparator;

public class FbrNaturalOrderComparator implements Comparator<String> {
    public static final FbrNaturalOrderComparator INSTANCE = new FbrNaturalOrderComparator();

    @Override
    public int compare(String a, String b) {
        int i = 0, j = 0;
        while (i < a.length() && j < b.length()) {
            char ca = a.charAt(i), cb = b.charAt(j);

            if (Character.isDigit(ca) && Character.isDigit(cb)) {
                int startI = i, startJ = j;
                while (i < a.length() && Character.isDigit(a.charAt(i))) i++;
                while (j < b.length() && Character.isDigit(b.charAt(j))) j++;

                String numA = a.substring(startI, i).replaceFirst("^0+(?!$)", "");
                String numB = b.substring(startJ, j).replaceFirst("^0+(?!$)", "");

                if (numA.length() != numB.length()) return numA.length() - numB.length();
                int cmp = numA.compareTo(numB);
                if (cmp != 0) return cmp;
            } else {
                if (ca != cb) return Character.compare(ca, cb);
                i++; j++;
            }
        }
        return (a.length() - i) - (b.length() - j);
    }
}
