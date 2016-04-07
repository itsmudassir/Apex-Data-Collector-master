package edu.dartmouth.cs.myrunscollector;




/**
 * Created by mudassir on 3/2/2016.
 */
public class Sample {
    public double x;
    public  double y;
    public double z;
public double rotX;


    public Sample(double x,double y,double z,double rot){
        setX(x);
        setY(y);
        setZ(z);
        setRotX(rot);


    }

    public void setX(double x) {
        this.x = x;
    }
    public void setRotX(double x) {
        this.rotX = x;
    }


    public void setY(double y) {
        this.y = y;
    }

    public void setZ(double z) {
        this.z = z;
    }

    public double getX() {
        return x;
    }
    public double getRotX() {
        return rotX;
    }

    public double getY() {
        return y;
    }

    public double getZ() {
        return z;
    }
}
