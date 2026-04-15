import org.emp.auth.*;
import java.rmi.registry.*;
import org.emp.common.*;

public class TestAuth {
    public static void main(String[] args) throws Exception {
        Registry registry = LocateRegistry.getRegistry("localhost", 1099);
        AuthService auth = (AuthService) registry.lookup(AuthService.BINDING_NAME);
        
        // Test admin authentication
        boolean result = auth.authenticate("admin", "admin123");
        System.out.println("Admin auth result: " + result);
        
        // List users
        System.out.println("Users: " + auth.listUsers());
    }
}
