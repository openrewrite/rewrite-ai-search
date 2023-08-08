import java.util.ArrayList;
import java.util.List;
import java.lang.Math;

public class VectorUtils {
    // public static void main(String args[]) {
    //     String string1 = "[0.123,-10.1, 0.1]";
    //     String string2 = "[0.113,-10.1, 0.1]";
    //     System.out.println(isRelated(string1, string2, 0.0755));    
    // }
    
    public static boolean isRelated(String input1, String input2, Double threshold){
        return calculateDistance(parseVector(input1), parseVector(input2))<=threshold;
    }
    
    public static List<Double> parseVector(String input) {
        List<Double> vector = new ArrayList<>();
        String[] elements = input.replaceAll("[\\[\\]]", "").split(",");
        
        for (String element : elements) {
            vector.add(Double.parseDouble(element.trim()));
        }
        
        return vector;
    }
    
    public static double calculateDistance(List<Double> vector1, List<Double> vector2) {
        if (vector1.size() != vector2.size()) {
            throw new IllegalArgumentException("Vectors must have the same dimension");
        }
        
        double sumOfSquaredDifferences = 0.0;
        for (int i = 0; i < vector1.size(); i++) {
            double diff = vector1.get(i) - vector2.get(i);
            sumOfSquaredDifferences += diff * diff;
        }
        
        return Math.sqrt(sumOfSquaredDifferences);
    }
}
