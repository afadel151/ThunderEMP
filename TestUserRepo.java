import org.emp.common.UserRepository;
import org.emp.common.UserDTO;
import java.util.List;

public class TestUserRepo {
    public static void main(String[] args) throws Exception {
        UserRepository repo = new UserRepository();
        
        System.out.println("Testing database connection...");
        System.out.println("DB Healthy: " + org.emp.common.DBConnection.isHealthy());
        
        System.out.println("Testing authentication...");
        boolean authResult = repo.authenticate("admin", "240be518fabd2724ddb6f04eeb1da5967448d7e831c08c8fa822809f74c720a9");
        System.out.println("Admin auth result: " + authResult);
        
        System.out.println("Testing list users...");
        List<UserDTO> users = repo.listUsers();
        System.out.println("Users count: " + users.size());
        for (UserDTO user : users) {
            System.out.println("User: " + user.getUsername() + " <" + user.getEmail() + "> [" + (user.isActive() ? "active" : "disabled") + "]");
        }
    }
}
