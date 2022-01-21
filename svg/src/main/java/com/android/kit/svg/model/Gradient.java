package com.android.kit.svg.model;

import android.graphics.Matrix;
import android.graphics.Shader;

import java.util.ArrayList;

public class Gradient {

    public String id;
    public String xlink;
    public boolean isLinear;
    public float x1, y1, x2, y2;
    public float x, y, radius;
    public ArrayList<Float> positions = new ArrayList<>();
    public ArrayList<Integer> colors = new ArrayList<>();
    public Matrix matrix = null;

    public Shader shader = null;
    public boolean boundingBox = false;
    public Shader.TileMode tileMode;

    public void inherit(Gradient parent) {
        xlink = parent.id;
        positions = parent.positions;
        colors = parent.colors;
        if (parent.matrix != null) {
            Matrix m = new Matrix(parent.matrix);
            m.preConcat(matrix);
            matrix = m;
        }
    }
}
