import java.util.Scanner;

  public class SumRange
  {
     public static void main(String[] args)
     {
        Scanner scan = new Scanner(System.in);

        System.out.print(“Enter the first number: ”);
        int a = scan.nextInt();

        System.out.print(“Enter the second number: ”);
        int b = scan.nextInt();

        System.out.println(“The sum is ” + sumRange(a, b));
     }

     // Write a function method called sumRange that accepts 2 int parameters and 
     // returns the sum of the ints between a and b inclusive. 
     // For example, sumRange(3, 5) will return 12 (since 3 + 4 + 5 = 12).


}
