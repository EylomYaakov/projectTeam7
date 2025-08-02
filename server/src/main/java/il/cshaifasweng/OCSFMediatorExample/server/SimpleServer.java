package il.cshaifasweng.OCSFMediatorExample.server;

import il.cshaifasweng.OCSFMediatorExample.entities.*;
import il.cshaifasweng.OCSFMediatorExample.server.ocsf.AbstractServer;
import il.cshaifasweng.OCSFMediatorExample.server.ocsf.ConnectionToClient;
import il.cshaifasweng.OCSFMediatorExample.server.ocsf.SubscribedClient;
import java.io.IOException;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;

public class SimpleServer extends AbstractServer {


	private static ArrayList<SubscribedClient> SubscribersList = new ArrayList<>();
	private static ArrayList<ConnectedUser> ConnectedList = new ArrayList<>();
	DatabaseManager databaseManager = new DatabaseManager();

	public SimpleServer(int port) {
		super(port);
		try {
			Class.forName("org.sqlite.JDBC");
		} catch (ClassNotFoundException e) {
			System.out.println("❌ SQLite JDBC driver not found.");
			e.printStackTrace();
		}
		DatabaseManager.connect(); // Connect using DatabaseManager
		DatabaseInitializer.initializeDatabase();

	}


	@Override
	protected void handleMessageFromClient(Object msg, ConnectionToClient client) {
		if(msg instanceof String) {

			String text = (String) msg;
			System.out.println(text);
			System.out.println("Database users: ");
			DatabaseManager.printAllUsers();
			System.out.println("Connected users: ");

			for (ConnectedUser cU : ConnectedList) {
				System.out.println("🟢 Connected: " +
						"Username = " + cU.getUsername() );
			}

			if (text.equals("GET_CATALOG")){
				// Client requested the full catalog
				SubscribedClient exists = findClient(client);
				if (exists == null) {
					SubscribedClient connection = new SubscribedClient(client);
					System.out.println(connection.getUsername());
					SubscribersList.add(connection);
				}
				databaseManager.sendCatalog(client);

			} else if (text.startsWith("GET_ITEM")) {
				// Format: GET_ITEM:<id>
				String[] parts = text.split(":");
				if (parts.length == 2) {
					int id = Integer.parseInt(parts[1]);
					sendItem(client, id);
				}

			} else if (text.startsWith("UPDATE_PRICE")) {
				// Format: UPDATE_PRICE:<id>:<newPrice>
				String[] parts = text.split(":");
				if (parts.length == 3) {
					int id = Integer.parseInt(parts[1]);
					double newPrice = Double.parseDouble(parts[2]);
					ChangePriceEvent event =DatabaseManager.updatePrice(client, id, newPrice);
					sendToAllClients(event);
				}

			} else if (text.startsWith("remove client")) {
				SubscribedClient toRemove = findClient(client);
				if (toRemove != null) {
					SubscribersList.remove(toRemove);
					if(!toRemove.getUsername().equals("~"))
						for (ConnectedUser user : ConnectedList) {
							if (user.getUsername().equals(toRemove.getUsername())) {
								ConnectedUser userToRemove = user;
								ConnectedList.remove(userToRemove);

								break;
							}
						}
				}

			}
			else if (text.startsWith("LOGIN:")) {
				LoginEvent event;
				Boolean response = handleLogin(text);
				String username = extractUsername(text);
				System.out.println(response);
				if (response) {
					boolean alreadyConnected = ConnectedList.stream()
							.anyMatch(user -> user.getUsername().equals(username));
					System.out.println(alreadyConnected);
					System.out.println(ConnectedList.size());

					if (!alreadyConnected) {
						ConnectedUser user = DatabaseManager.getUser(username);
						SubscribedClient subscribedClient = findClient(client);
						subscribedClient.setUsername(username);
						ConnectedList.add(user);
						System.out.println("LOGIN_SUCCESS");
						event = new LoginEvent("LOGIN_SUCCESS");
					} else {
						event = new LoginEvent("Already logged in");
					}
				}
				else {

					 event = new LoginEvent("LOGIN_FAIL");
				}
				try {
					client.sendToClient(event);
					} catch (IOException e) {
						e.printStackTrace();
					}
				}

			else if (text.startsWith("LOGOUT:")) {
				String username = extractUsername(text);
				ConnectedUser user = DatabaseManager.getUser(username);

				boolean removed = ConnectedList.removeIf(u -> u.getUsername().equals(username));

				SubscribedClient subscribedClient = findClient(client);
				if (subscribedClient != null) {
					subscribedClient.setUsername(null); // איפוס המשתמש
				}

				LogoutEvent event;
				if (removed) {
					System.out.println("LOGOUT_OK");
					event = new LogoutEvent("LOGOUT_OK");
				} else {
					System.out.println("LOGOUT_NOT_FOUND");
					event = new LogoutEvent("LOGOUT_NOT_FOUND");
				}

				try {
					client.sendToClient(event);
				} catch (IOException e) {
					e.printStackTrace();
				}
			}


			else {
					// Unknown message received
					System.out.println("!! Unknown message format received: " + text);
				}

		}

		else if(msg instanceof ConnectedUser) {
			ConnectedUser user = (ConnectedUser)msg;
			SignUpEvent event;
			String username = user.getUsername();
			String password = user.getPassword();
			String personalId = user.getUserID();
			String creditId = user.getCreditCard();
			String role = user.getRole();// can contain spaces like "chain account"
			boolean userExists = DatabaseManager.userExists(username);
			if (userExists) {
				event = new SignUpEvent("USERNAME_TAKEN");
				System.out.println("USERNAME_TAKEN");
			}
			else {
				boolean created = DatabaseManager.createUser(username, password, personalId, creditId, role);
				if (created) {
					ConnectedUser newUser = DatabaseManager.getUser(username);
					ConnectedList.add(newUser);
					event = new SignUpEvent("SIGNUP_SUCCESS");
					System.out.println("SIGNUP_SUCCESS");
				} else {
					event = new SignUpEvent("SIGNUP_FAILED");
					System.out.println("SIGNUP_FAILED");

				}
			}
			try{
				client.sendToClient(event);
			}
			catch (IOException e) {
				e.printStackTrace();
			}

		}
	}


		private String extractUsername (String loginMsg){
			String[] parts = loginMsg.split(":", 3);
			return parts.length >= 2 ? parts[1] : "";
		}


		public Boolean handleLogin (String text){
			String[] parts = text.split(":", 3); // Split into 3 parts max
			if (parts.length == 3) {
				String username = parts[1];
				String password = parts[2];

				//  Replace with real authentication logic
				boolean isAuthenticated = databaseManager.checkCredentials(username, password);
				return isAuthenticated;
			} else {
				return false;
			}
		}



		public SubscribedClient findClient (ConnectionToClient client){
			if (!SubscribersList.isEmpty()) {
				for (SubscribedClient subscribedClient : SubscribersList) {
					if (subscribedClient.getClient().equals(client)) {
						SubscribersList.remove(subscribedClient);
						return subscribedClient;
					}
				}
			}
			return null;
		}


		public void sendToAllClients (Object message){
			try {
				for (SubscribedClient subscribedClient : SubscribersList) {
					subscribedClient.getClient().sendToClient(message);
				}
			} catch (IOException e1) {
				e1.printStackTrace();
			}
		}




		private void sendItem (ConnectionToClient client,int id){
			Product product = DatabaseManager.getItem(client, id);
			InitDescriptionEvent event = new InitDescriptionEvent(product);
			try {
				client.sendToClient(event);
			} catch (IOException e) {
				e.printStackTrace();
			}
		}




	}
