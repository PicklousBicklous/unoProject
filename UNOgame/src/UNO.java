import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.net.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Random;
import java.util.Scanner;
import javax.swing.*;
import javax.swing.border.Border;

public class UNO {
    static boolean guiMode = false;

    private static final Color TEXT_COLOR = Color.WHITE;

    JFrame frame;
    JLabel handTitleLabel;
    JLabel statusLabel;
    JPanel handPanel;
    JPanel othersPanel;          // other players' hands (card backs, or faces when revealed)
    JButton drawPileButton;
    JLabel discardPileLabel;
    Game game;

    boolean revealAll = false;   // admin: show every hand face-up (and let you play from them)
    int viewerIndex = -1;        // -1 = hot-seat (show whoever's turn it is); >= 0 = this window is that player

    // multiplayer: host keeps one writer per remote player (client i controls player i+1)
    java.util.List<PrintWriter> clients = new java.util.concurrent.CopyOnWriteArrayList<>();
    PrintWriter toHost;          // set on a joined client

    public UNO() {
        Dimension screenSize = Toolkit.getDefaultToolkit().getScreenSize();
        int SCREEN_X = (int) screenSize.getWidth() * 3 / 4;
        int SCREEN_Y = (int) screenSize.getHeight() * 3 / 4;

        guiMode = true;
        frame = new JFrame("UNO");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setContentPane(new TablePanel(new BorderLayout(10, 10))); // image/gradient table background
        frame.setSize(SCREEN_X, SCREEN_Y);
        frame.setLocationRelativeTo(null);
        frame.setJMenuBar(buildAdminMenu());

        statusLabel = new JLabel("Choose the number of players to begin.");
        statusLabel.setHorizontalAlignment(SwingConstants.CENTER);
        statusLabel.setFont(new Font("SansSerif", Font.BOLD, 18));
        statusLabel.setForeground(TEXT_COLOR);

        othersPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 15, 5));
        othersPanel.setOpaque(false);
        JPanel topPanel = new JPanel(new BorderLayout());
        topPanel.setOpaque(false);
        topPanel.add(statusLabel, BorderLayout.NORTH);
        topPanel.add(othersPanel, BorderLayout.CENTER);
        frame.add(topPanel, BorderLayout.NORTH);

        JPanel centerPanel = new JPanel(new GridLayout(1, 2, 20, 20));
        centerPanel.setOpaque(false);

        drawPileButton = new JButton();
        drawPileButton.setIcon(loadCardIcon("./cards/card back/card_back.png", 150, 210));
        drawPileButton.setText("Draw");
        drawPileButton.setVerticalTextPosition(SwingConstants.BOTTOM);
        drawPileButton.setHorizontalTextPosition(SwingConstants.CENTER);
        drawPileButton.setFocusPainted(false);
        drawPileButton.setContentAreaFilled(false);
        drawPileButton.addActionListener(e -> drawAction());
        drawPileButton.setBorder(BorderFactory.createTitledBorder("Draw Pile"));
        addHover(drawPileButton, drawPileButton.getBorder(),
                BorderFactory.createTitledBorder(BorderFactory.createLineBorder(Color.YELLOW, 3), "Draw Pile"));

        discardPileLabel = new JLabel();
        discardPileLabel.setBorder(BorderFactory.createTitledBorder("Discard Pile"));
        discardPileLabel.setHorizontalAlignment(SwingConstants.CENTER);
        discardPileLabel.setVerticalAlignment(SwingConstants.CENTER);

        centerPanel.add(drawPileButton);
        centerPanel.add(discardPileLabel);
        frame.add(centerPanel, BorderLayout.CENTER);

        JPanel handWrapper = new JPanel(new BorderLayout());
        handWrapper.setOpaque(false);

        handTitleLabel = new JLabel("Your Hand", SwingConstants.CENTER);
        handTitleLabel.setFont(new Font("SansSerif", Font.BOLD, 14));
        handTitleLabel.setForeground(TEXT_COLOR);
        handWrapper.add(handTitleLabel, BorderLayout.NORTH);

        handPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 10));
        handPanel.setOpaque(false);
        handWrapper.add(handPanel, BorderLayout.CENTER);

        frame.add(handWrapper, BorderLayout.SOUTH);

        frame.setVisible(true);

        int mode = JOptionPane.showOptionDialog(frame, "Start a new game or join one on another device?", "UNO",
                JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE, null,
                new Object[]{"New game", "Join game"}, "New game");
        if (mode == 1) joinGame(); else promptForPlayerCount();
    }

    // ---------- Admin menu ----------
    private JMenuBar buildAdminMenu() {
        JMenu admin = new JMenu("Admin");

        JMenuItem restart = new JMenuItem("Restart game");
        restart.addActionListener(e -> restartGame());

        JMenuItem shuffleDeck = new JMenuItem("Shuffle deck");
        shuffleDeck.addActionListener(e -> {
            if (game != null) { game.deck.shuffle(); statusLabel.setText("Deck shuffled."); }
        });

        JMenuItem shuffleHand = new JMenuItem("Shuffle current hand");
        shuffleHand.addActionListener(e -> {
            if (game != null) { Collections.shuffle(game.players.get(game.currentPlayerIndex).hand); refreshBoard(); }
        });

        JCheckBoxMenuItem reveal = new JCheckBoxMenuItem("Reveal all hands");
        reveal.addActionListener(e -> { revealAll = reveal.isSelected(); refreshBoard(); });

        JMenuItem force = new JMenuItem("Force draw (no turn skip)");
        force.addActionListener(e -> {
            if (game != null && game.winner == null) {
                game.players.get(game.currentPlayerIndex).drawFromDeck(game.deck, 1);
                refreshBoard();
            }
        });

        JMenuItem host = new JMenuItem("Host multiplayer...");
        host.addActionListener(e -> hostGame());

        admin.add(restart);
        admin.add(shuffleDeck);
        admin.add(shuffleHand);
        admin.add(reveal);
        admin.add(force);
        admin.addSeparator();
        admin.add(host);

        JMenuBar bar = new JMenuBar();
        bar.add(admin);
        return bar;
    }

    private void restartGame() {
        if (game == null) return;
        ArrayList<Player> fresh = new ArrayList<>();
        for (Player p : game.players) fresh.add(new Player(p.name));
        game = new Game();
        game.gameStart(fresh);
        refreshBoard();
    }

    // @retrieve player count from user
    private void promptForPlayerCount() {
        int chosenCount = 2;
        String input = JOptionPane.showInputDialog(frame, "Choose the number of players (2-4):", "UNO", JOptionPane.QUESTION_MESSAGE);
        if (input != null && !input.trim().isEmpty()) {
            try {
                chosenCount = Integer.parseInt(input.trim());
            } catch (NumberFormatException ex) {
                System.out.println("That is not an integer. Try again:");
            }
        }

        while (chosenCount < 2 || chosenCount > 4) {
            System.out.println("Invalid number of players. Please enter a number between 2 and 4.");
            input = JOptionPane.showInputDialog(frame, "Choose the number of players (2-4):", "UNO", JOptionPane.QUESTION_MESSAGE);
            if (input == null) {
                chosenCount = 2;
                break;
            }
            try {
                chosenCount = Integer.parseInt(input.trim());
            } catch (NumberFormatException ex) {
                chosenCount = 2;
            }
        }

        ArrayList<Player> players = new ArrayList<>();
        ArrayList<String> takenNames = new ArrayList<>();
        for (int i = 1; i <= chosenCount; i++) {
            String defaultName = "Player " + i;
            String name = JOptionPane.showInputDialog(frame, "Enter a name for Player " + i + ":", "UNO", JOptionPane.QUESTION_MESSAGE);
            if (name != null) {
                name = name.trim();
            }
            if (name == null || name.isEmpty()) {
                name = defaultName;
            }
            String uniqueName = name;
            int suffix = 2;
            while (takenNames.contains(uniqueName)) {
                uniqueName = name + " (" + suffix + ")";
                suffix++;
            }
            takenNames.add(uniqueName);
            players.add(new Player(uniqueName));
        }

        game = new Game();
        game.gameStart(players);
        refreshBoard();
    }

    private void refreshBoard() {
        if (game == null) return;

        if (game.winner != null) {
            statusLabel.setText("Winner: " + game.winner.name);
            handTitleLabel.setText("");
            handPanel.removeAll();
            othersPanel.removeAll();
            handPanel.revalidate();
            handPanel.repaint();
            othersPanel.repaint();
            broadcastState();
            JOptionPane.showMessageDialog(frame, "Winner: " + game.winner.name, "UNO", JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        Player current = game.players.get(game.currentPlayerIndex);
        Player viewer = game.players.get(viewerIndex >= 0 ? viewerIndex : game.currentPlayerIndex);

        statusLabel.setText(current.name + "'s turn. Top card: " + game.topCard.toString() + ", Active colour: " + game.activeColour);
        discardPileLabel.setIcon(loadCardIcon(game.topCard.getImagePath(), 150, 210));
        discardPileLabel.setBorder(BorderFactory.createLineBorder(colourFor(game.activeColour), 4));

        handTitleLabel.setText(viewer.name + "'s Hand (" + viewer.hand.size() + " cards)");
        fillHand(handPanel, viewer, true, 90, 130);

        // everyone else: card backs, or clickable faces when "Reveal all hands" is on
        othersPanel.removeAll();
        for (Player p : game.players) {
            if (p == viewer) continue;
            JLabel name = new JLabel(p.name + " (" + p.hand.size() + ")", SwingConstants.CENTER);
            name.setForeground(p == current ? Color.YELLOW : TEXT_COLOR);
            JPanel cards = new JPanel(new FlowLayout(FlowLayout.CENTER, 2, 2));
            cards.setOpaque(false);
            fillHand(cards, p, revealAll, 45, 65);
            JPanel box = new JPanel(new BorderLayout());
            box.setOpaque(false);
            box.add(name, BorderLayout.NORTH);
            box.add(cards, BorderLayout.CENTER);
            othersPanel.add(box);
        }
        othersPanel.revalidate();
        othersPanel.repaint();
        frame.repaint();
        broadcastState();
    }

    // fills a panel with one player's cards; face-up cards are clickable and play for that player
    private void fillHand(JPanel panel, Player owner, boolean faceUp, int w, int h) {
        panel.removeAll();
        for (Card card : owner.hand) {
            JButton b = makeCardButton(faceUp ? card.getImagePath() : "./cards/card back/card_back.png", w, h);
            if (faceUp) {
                b.setToolTipText(card.toString());
                if (card.matches(game.topCard, game.activeColour)) {
                    b.setBorder(BorderFactory.createLineBorder(Color.WHITE, 3));
                }
                addHover(b, b.getBorder(), BorderFactory.createLineBorder(Color.YELLOW, 3));
                b.addActionListener(e -> tryPlay(owner, card));
            }
            panel.add(b);
        }
        panel.revalidate();
        panel.repaint();
    }

    private void tryPlay(Player owner, Card card) {
        if (game == null || game.winner != null) return;
        Player turnPlayer = game.players.get(game.currentPlayerIndex);

        if (!card.matches(game.topCard, game.activeColour)) {
            statusLabel.setText("Illegal card choice. You must choose a card that matches the top card or draw.");
            return;
        }
        if (owner != turnPlayer) {
            if (!revealAll) { statusLabel.setText("It's not your turn."); return; }
            game.currentPlayerIndex = game.players.indexOf(owner); // admin override: play from any revealed hand
        }

        System.out.println(owner.name + " plays " + card.toString());
        game.playSelectedCard(owner, card, new Scanner(System.in));
        refreshBoard();
    }

    private void drawAction() {
        if (toHost != null) { toHost.println("DRAW"); return; }
        if (game == null || game.winner != null) return;
        if (viewerIndex >= 0 && viewerIndex != game.currentPlayerIndex && !revealAll) return;
        Player current = game.players.get(game.currentPlayerIndex);
        System.out.println(current.name + " draws a card from the draw pile.");
        current.drawFromDeck(game.deck, 1);
        game.advanceTurn();
        refreshBoard();
    }

    private JButton makeCardButton(String path, int w, int h) {
        JButton b = new JButton(loadCardIcon(path, w, h));
        b.setText("");
        b.setBorder(BorderFactory.createEmptyBorder());
        b.setContentAreaFilled(false);
        b.setFocusPainted(false);
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return b;
    }

    // rollover effect: swap the border while the mouse is over the component
    private static void addHover(JComponent c, Border normal, Border hover) {
        c.addMouseListener(new MouseAdapter() {
            public void mouseEntered(MouseEvent e) { c.setBorder(hover); }
            public void mouseExited(MouseEvent e) { c.setBorder(normal); }
        });
    }

    // ---------- Multiplayer (host = authority, clients send PLAY/DRAW, host sends STATE) ----------
    private void hostGame() {
        if (game == null || toHost != null) return;
        String in = JOptionPane.showInputDialog(frame, "Port to host on:", "5000");
        if (in == null) return;
        try {
            ServerSocket server = new ServerSocket(Integer.parseInt(in.trim()));
            viewerIndex = 0; // this window is now Player 1; the others join from their own devices
            new Thread(() -> acceptClients(server)).start();
            JOptionPane.showMessageDialog(frame, "Hosting at " + InetAddress.getLocalHost().getHostAddress() + ":" + server.getLocalPort()
                    + "\nPlayers 2-" + game.players.size() + " join in the order they connect.");
            refreshBoard();
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(frame, "Could not host: " + ex.getMessage());
        }
    }

    private void acceptClients(ServerSocket server) {
        try {
            while (true) {
                Socket s = server.accept();
                int seat = clients.size() + 1;
                if (seat >= game.players.size()) { s.close(); continue; } // table is full
                clients.add(new PrintWriter(s.getOutputStream(), true));
                new Thread(() -> listenToClient(s, seat)).start();
                SwingUtilities.invokeLater(this::refreshBoard);
            }
        } catch (IOException ex) {
            System.out.println("Server stopped: " + ex.getMessage());
        }
    }

    private void listenToClient(Socket s, int seat) {
        try (BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream()))) {
            String line;
            while ((line = in.readLine()) != null) {
                String[] cmd = line.split(" ");
                SwingUtilities.invokeLater(() -> handleRemote(seat, cmd));
            }
        } catch (IOException ex) {
            System.out.println("Player " + (seat + 1) + " disconnected.");
        }
    }

    // commands from a remote player: "PLAY <cardIndex> [colour]" or "DRAW"
    private void handleRemote(int seat, String[] cmd) {
        if (game == null || game.winner != null || game.currentPlayerIndex != seat) return;
        Player p = game.players.get(seat);
        if (cmd[0].equals("DRAW")) {
            p.drawFromDeck(game.deck, 1);
            game.advanceTurn();
        } else if (cmd[0].equals("PLAY") && cmd.length >= 2) {
            int i = Integer.parseInt(cmd[1]);
            if (i < 0 || i >= p.hand.size() || !p.hand.get(i).matches(game.topCard, game.activeColour)) return;
            if (cmd.length > 2) p.presetColour = cmd[2];
            game.playSelectedCard(p, p.hand.get(i), null);
        }
        refreshBoard();
    }

    // host -> clients: STATE|status|topCard|activeColour|hand|winner  (each client only gets its own hand)
    private void broadcastState() {
        if (game == null) return;
        for (int i = 0; i < clients.size() && i + 1 < game.players.size(); i++) {
            Player p = game.players.get(i + 1);
            Player cur = game.players.get(game.currentPlayerIndex);
            StringBuilder hand = new StringBuilder();
            for (Card c : p.hand) hand.append(hand.length() > 0 ? "," : "").append(c.toString());
            String status = (cur == p ? "Your turn" : cur.name + "'s turn");
            for (Player o : game.players) if (o != p) status += ", " + o.name + ": " + o.hand.size();
            clients.get(i).println("STATE|" + status.replace("|", "/") + "|" + game.topCard + "|" + game.activeColour
                    + "|" + hand + "|" + (game.winner == null ? "" : game.winner.name.replace("|", "/")));
        }
    }

    private void joinGame() {
        String host = JOptionPane.showInputDialog(frame, "Host address:", "localhost");
        String port = JOptionPane.showInputDialog(frame, "Port:", "5000");
        if (host == null || port == null) return;
        try {
            Socket s = new Socket(host.trim(), Integer.parseInt(port.trim()));
            toHost = new PrintWriter(s.getOutputStream(), true);
            statusLabel.setText("Connected. Waiting for the host...");
            new Thread(() -> listenToHost(s)).start();
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(frame, "Could not join: " + ex.getMessage());
        }
    }

    private void listenToHost(Socket s) {
        try (BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream()))) {
            String line;
            while ((line = in.readLine()) != null) {
                String[] f = line.split("\\|", -1);
                if (f[0].equals("STATE")) SwingUtilities.invokeLater(() -> renderRemote(f));
            }
        } catch (IOException ex) { }
        SwingUtilities.invokeLater(() -> statusLabel.setText("Disconnected from host (or the game is full)."));
    }

    // client side: draw the state the host sent
    private void renderRemote(String[] f) {
        Card top = Card.fromString(f[2]);
        statusLabel.setText(f[5].isEmpty() ? f[1] + "  |  Top card: " + f[2] + ", Active colour: " + f[3] : "Winner: " + f[5]);
        discardPileLabel.setIcon(loadCardIcon(top.getImagePath(), 150, 210));
        discardPileLabel.setBorder(BorderFactory.createLineBorder(colourFor(f[3]), 4));

        String[] cards = f[4].isEmpty() ? new String[0] : f[4].split(",");
        handTitleLabel.setText("Your Hand (" + cards.length + " cards)");
        handPanel.removeAll();
        for (int i = 0; i < cards.length; i++) {
            Card card = Card.fromString(cards[i]);
            int index = i;
            JButton b = makeCardButton(card.getImagePath(), 90, 130);
            b.setToolTipText(card.toString());
            if (card.matches(top, f[3])) b.setBorder(BorderFactory.createLineBorder(Color.WHITE, 3));
            addHover(b, b.getBorder(), BorderFactory.createLineBorder(Color.YELLOW, 3));
            b.addActionListener(e -> {
                if (!card.matches(top, f[3])) {
                    statusLabel.setText("Illegal card choice. You must choose a card that matches the top card or draw.");
                    return;
                }
                boolean wild = card.getType().equals(Card.WILD) || card.getType().equals(Card.WILD_DRAW_FOUR);
                toHost.println("PLAY " + index + (wild ? " " + chooseColourDialog() : ""));
            });
            handPanel.add(b);
        }
        handPanel.revalidate();
        handPanel.repaint();
        if (!f[5].isEmpty()) JOptionPane.showMessageDialog(frame, "Winner: " + f[5], "UNO", JOptionPane.INFORMATION_MESSAGE);
    }

    private static Color colourFor(String colour) {
        if (colour.equals(Card.RED)) {
            return Color.RED;
        } else if (colour.equals(Card.YELLOW)) {
            return Color.YELLOW;
        } else if (colour.equals(Card.GREEN)) {
            return Color.GREEN;
        } else if (colour.equals(Card.BLUE)) {
            return Color.BLUE;
        } else {
            return Color.WHITE;
        }
    }

    private static Image loadImage(String path) {
        String resourcePath = path.replace('\\', '/').replaceFirst("^\\./", "");
        URL resource = UNO.class.getResource("/" + resourcePath);
        if (resource != null) {
            return new ImageIcon(resource).getImage();
        }

        File file = new File(path);
        if (!file.exists()) {
            file = new File("./src/" + resourcePath);
        }
        if (file.exists()) {
            return new ImageIcon(file.getAbsolutePath()).getImage();
        }

        System.err.println("Unable to load image '" + path + "' (resource '" + resourcePath + "' not found).");
        return null;
    }

    private static ImageIcon loadCardIcon(String path, int width, int height) {
        Image img = loadImage(path);
        if (img == null) {
            return new ImageIcon(new java.awt.image.BufferedImage(width, height, java.awt.image.BufferedImage.TYPE_INT_ARGB));
        }
        return new ImageIcon(img.getScaledInstance(width, height, Image.SCALE_SMOOTH));
    }

    private static String chooseColourDialog() {
        Object[] options = {"Red", "Yellow", "Green", "Blue"};
        int choice = JOptionPane.showOptionDialog(null, "Choose a colour:", "UNO",
                JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE, null, options, options[0]);

        switch (choice) {
            case 0: return Card.RED;
            case 1: return Card.YELLOW;
            case 2: return Card.GREEN;
            default: return Card.BLUE;
        }
    }

    // ---------- Table background: ./cards/background/table.png if present, otherwise a green gradient ----------
    static class TablePanel extends JPanel {
        private final Image img = loadImage("./cards/background/table.png");

        TablePanel(LayoutManager layout) {
            super(layout);
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            if (img != null) {
                g.drawImage(img, 0, 0, getWidth(), getHeight(), this);
            } else {
                ((Graphics2D) g).setPaint(new GradientPaint(0, 0, new Color(0, 140, 70), 0, getHeight(), new Color(0, 50, 20)));
                g.fillRect(0, 0, getWidth(), getHeight());
            }
        }
    }

    // ---------- Card ----------
    static class Card {
        static final String RED = "red";
        static final String YELLOW = "yellow";
        static final String GREEN = "green";
        static final String BLUE = "blue";
        static final String WILD_COLOUR = "wild";

        static final String NUMBER = "number";
        static final String SKIP = "skip";
        static final String REVERSE = "reverse";
        static final String DRAW_TWO = "draw_two";
        static final String WILD = "wild";
        static final String WILD_DRAW_FOUR = "wild_draw_four";

        String colour;
        String type;
        int value; // only used when type == NUMBER

        Card(String colour, String type) {
            this(colour, type, -1);
        }

        Card(String c, String t, int val) {
            colour = c;
            this.type = t;
            this.value = val;
        }

        // rebuilds a card from its toString() text, e.g. "5_blue", "draw_two_red", "wild"
        static Card fromString(String s) {
            if (s.equals(WILD)) return new Card(WILD_COLOUR, WILD);
            if (s.equals(WILD_DRAW_FOUR)) return new Card(WILD_COLOUR, WILD_DRAW_FOUR);
            int cut = s.lastIndexOf('_');
            String front = s.substring(0, cut);
            String col = s.substring(cut + 1);
            if (Character.isDigit(front.charAt(0))) return new Card(col, NUMBER, Integer.parseInt(front));
            return new Card(col, front);
        }

        String getColour() {
            return colour;
        }

        String getType() { return type; }

        int getValue()
        {
            if (type.equals(NUMBER)) {
                return value;
            } else {
                return -1; // not a number card, no value to return
            }
        }

        // checks if this card can be played on top of topCard, given the active colour
        boolean matches(Card topCard, String activeColour) {
            if (type.equals(WILD) || type.equals(WILD_DRAW_FOUR)) return true;
            if (colour.equals(activeColour)) {
                return true;
            }
            if (type.equals(topCard.type)) {
                if (type.equals(NUMBER)) {
                    return value == topCard.value;
                }
                return true;
            }
            return false;
        }

        // Adamson's UNO toString: value_colour, e.g. "0_blue"
        public String toString() {
            if (type.equals(NUMBER)) {
                return value + "_" + colour;
            }
            if (colour.equals(WILD_COLOUR)) {
                return type;
            }
            return type + "_" + colour;
        }

        // ./cards/blue/0_blue.png
        String getImagePath() {
            if (type.equals(NUMBER)) {
                return "./cards/" + colour + "/" + value + "_" + colour + ".png";
            }
            if (type.equals(SKIP)) {
                return "./cards/" + colour + "/block_" + colour + ".png";
            }
            if (type.equals(REVERSE)) {
                return "./cards/" + colour + "/inverse_" + colour + ".png";
            }
            if (type.equals(DRAW_TWO)) {
                return "./cards/" + colour + "/2plus_" + colour + ".png";
            }
            if (type.equals(WILD)) {
                return "./cards/wild/wild_card.png";
            }
            if (type.equals(WILD_DRAW_FOUR)) {
                return "./cards/wild/4_plus.png";
            }
            return "./cards/" + colour + "/" + toString() + ".png";
        }
    }

    // ---------- Deck ----------
    static class Deck {
        private final Random random = new Random();
        ArrayList<Card> drawPile = new ArrayList<>();
        ArrayList<Card> discardPile = new ArrayList<Card>();

        void buildDeck() {
            drawPile.clear();

            for (String c : new String[]{Card.RED, Card.YELLOW, Card.GREEN, Card.BLUE}) {
                drawPile.add(new Card(c, Card.NUMBER, 0)); // one zero

                for (int v = 1; v <= 9; v++) {
                    drawPile.add(new Card(c, Card.NUMBER, v));
                    drawPile.add(new Card(c, Card.NUMBER, v)); // two of each 1-9
                }

                for (int i = 0; i < 2; i++) {
                    drawPile.add(new Card(c, Card.SKIP));
                    drawPile.add(new Card(c, Card.REVERSE));
                    drawPile.add(new Card(c, Card.DRAW_TWO));
                }
            }

            for (int i=0; i<4; i++) {
                drawPile.add(new Card(Card.WILD_COLOUR, Card.WILD));
                drawPile.add(new Card(Card.WILD_COLOUR, Card.WILD_DRAW_FOUR));
            }
        }

        void shuffle() {
            for (int i = drawPile.size() - 1; i > 0; i--) {
                int j = random.nextInt(i + 1);
                Card temp = drawPile.get(i);
                drawPile.set(i, drawPile.get(j));
                drawPile.set(j, temp);
            }
        }

        int numberOfCardsInDrawPile() {
            return drawPile.size();
        }

        Card drawCard() {
            if (numberOfCardsInDrawPile() == 0)
                reshuffleDiscardIntoDraw();
            return drawPile.remove(drawPile.size() - 1);
        }

        void reshuffleDiscardIntoDraw() {
            if (discardPile.isEmpty()) {
                return; // nothing to reshuffle, shouldn't really happen
            }
            Card top = discardPile.remove(discardPile.size()-1);
            drawPile.addAll(discardPile);
            discardPile.clear();
            discardPile.add(top);
            shuffle();
        }

        Card flipStartingCard() {
            Card c = drawCard();
            if (c.getType().equals(Card.WILD_DRAW_FOUR) || c.getType().equals(Card.WILD)) {
                drawPile.add(c);
                shuffle();
                return flipStartingCard();
            }
            discardPile.add(c);
            return c;
        }
    }

    // ---------- Player ----------
    static class Player
    {
        String name;
        ArrayList<Card> hand = new ArrayList<>();
        boolean isAI;
        boolean hasCalledUno = false;
        String presetColour; // colour a remote player already picked on their own device

        Player(String n) {
            this.name = n;
        }

        void drawFromDeck(Deck deck, int count) {
            for (int i = 0; i < count; i++) {
                hand.add(deck.drawCard());
            }
        }

        boolean isHandEmpty() { return hand.size() == 0; }

        // picks the first legal card directly without creating a temporary list
        int firstPlayableCardIndex(Card topCard, String activeColour) {
            for (int i = 0; i < hand.size(); i++) {
                if (hand.get(i).matches(topCard, activeColour)) {
                    return i;
                }
            }
            return -1; // no legal card in hand
        }

        // asks the player to pick a colour for a wild / wild draw four card
        String chooseColour(Scanner scanner) {
            if (presetColour != null) {
                String c = presetColour;
                presetColour = null;
                return c;
            }
            if (guiMode) {
                return chooseColourDialog();
            }

            System.out.println("Choose a colour: 1 for red, 2 for yellow, 3 for green, 4 for blue");
            int pick = scanner.nextInt();

            while (pick < 1 || pick > 4) {
                System.out.println("Invalid choice. Choose a colour: 1 for red, 2 for yellow, 3 for green, 4 for blue");
                pick = scanner.nextInt();
            }

            switch (pick) {
                case 1: return Card.RED;
                case 2: return Card.YELLOW;
                case 3: return Card.GREEN;
                default: return Card.BLUE;
            }
        }
    }

    // ---------- Game ----------
    static class Game {
        ArrayList<Player> players;
        Deck deck;
        int currentPlayerIndex;
        int direction = 1; // 1 = clockwise, -1 = counter-clockwise
        String activeColour;
        Card topCard;
        Player winner;
        boolean turnAlreadyAdvanced;

        void gameStart(ArrayList<Player> playerList) {
            players = playerList;
            deck = new Deck();
            deck.buildDeck();
            deck.shuffle();
            System.out.println("Deck built and shuffled.");
            for (Player p : players) {
                p.drawFromDeck(deck, 7);
            }
            System.out.println("Dealt 7 cards to each player.");

            topCard = deck.flipStartingCard();
            System.out.println("Starting card: " + topCard.toString());
            activeColour = topCard.getColour();
            currentPlayerIndex = 0;
            System.out.println("Game started. First player: " + players.get(currentPlayerIndex).name);
        }

        int nextPlayerIndex() {
            return nextPlayerIndex(1);
        }

        int nextPlayerIndex(int skipCount) {
            int n = players.size();
            int idx = currentPlayerIndex + (direction * skipCount);
            idx = ((idx % n) + n) % n;
            return idx;
        }

        void advanceTurn() {
            currentPlayerIndex = nextPlayerIndex();
        }

        void playTurn(Scanner scanner) {
            Player player = players.get(currentPlayerIndex);

            System.out.println(player.name + "'s turn. Top card: " + topCard.toString() + ", Active colour: "
             + activeColour + ", number of cards in hand = " + player.hand.size());
            for (int i = 0; i < player.hand.size(); i++) {
                System.out.println("  " + (i + 1) + ": " + player.hand.get(i).toString());
            }
            System.out.println("pick a card (1 for first card) or draw (0): ");
            while (!scanner.hasNextInt()) {
                System.out.println("That is not an integer. Try again:");
                scanner.next();
            }
            int numberChosen = scanner.nextInt();

            if (numberChosen == 0) {
                player.drawFromDeck(deck, 1);
                advanceTurn();
                return;
            }

            Card chosen = null;
            while (chosen == null) {
                while (numberChosen < 1 || numberChosen > player.hand.size()) {
                    System.out.println("Invalid choice. Pick a card (1 for first card) or draw (0): ");
                    numberChosen = scanner.nextInt();
                    if (numberChosen == 0) {
                        player.drawFromDeck(deck, 1);
                        advanceTurn();
                        return;
                    }
                }

                Card candidate = player.hand.get(numberChosen - 1);
                if (candidate.matches(topCard, activeColour)) {
                    chosen = candidate;
                } else {
                    System.out.println("Illegal card choice. You must choose a card that matches the top card or draw.");
                    System.out.println("pick a card (1 for first card) or draw (0): ");
                    numberChosen = scanner.nextInt();
                    if (numberChosen == 0) {
                        player.drawFromDeck(deck, 1);
                        advanceTurn();
                        return;
                    }
                }
            }

            playSelectedCard(player, chosen, scanner);
        }

        void playSelectedCard(Player player, Card card, Scanner scanner) {
            player.hand.remove(card);
            deck.discardPile.add(card);
            topCard = card;
            turnAlreadyAdvanced = false;

            applyCardEffect(card, player, scanner);

            if (player.isHandEmpty()) {
                winner = player;
                endGame();
                return;
            }

            if (!turnAlreadyAdvanced) advanceTurn();
        }

        //Special Cards
        void applyCardEffect(Card card, Player player, Scanner scanner) {
            switch (card.getType()) {
                case Card.NUMBER:
                    activeColour = card.getColour();
                    break;

                case Card.SKIP:
                    activeColour = card.getColour();
                    System.out.println(players.get(nextPlayerIndex()).name + " is skipped!");
                    currentPlayerIndex = nextPlayerIndex(1);
                    advanceTurn();
                    turnAlreadyAdvanced = true;
                    break;

                case Card.REVERSE:
                    activeColour = card.getColour();
                    if (players.size() == 2) {
                        // with only 2 players, reverse just acts like a skip
                        advanceTurn();
                        advanceTurn();
                    }
                    else {
                        direction *= -1;
                        System.out.println("Direction reversed!");
                        advanceTurn();
                    }
                    turnAlreadyAdvanced = true;
                    break;

                case Card.DRAW_TWO:
                    activeColour = card.getColour();
                    Player drawTwoTarget = players.get(nextPlayerIndex());
                    drawTwoTarget.drawFromDeck(deck, 2);
                    System.out.println(drawTwoTarget.name + " draws 2 cards and is skipped!");
                    currentPlayerIndex = nextPlayerIndex(1);
                    advanceTurn();
                    turnAlreadyAdvanced = true;
                    break;

                case Card.WILD:
                    activeColour = player.chooseColour(scanner);
                    System.out.println(player.name + " chose " + activeColour + " as the active colour.");
                    break;

                case Card.WILD_DRAW_FOUR:
                    activeColour = player.chooseColour(scanner);
                    System.out.println(player.name + " chose " + activeColour + " as the active colour.");
                    Player wd4Target = players.get(nextPlayerIndex());
                    wd4Target.drawFromDeck(deck, 4);
                    System.out.println(wd4Target.name + " draws 4 cards and is skipped!");
                    currentPlayerIndex = nextPlayerIndex(1);
                    advanceTurn();
                    turnAlreadyAdvanced = true;
                    break;
            }
        }

        void endGame() {
            System.out.println(winner.name + " wins the game!");
        }
    }

    // ---------- Main ----------
    public static void main(String[] args) {
        // GUI by default; run with the argument "terminal" for the console version
        if (args.length == 0 || !args[0].equals("terminal")) {
            SwingUtilities.invokeLater(() -> new UNO());
            return;
        }

        ArrayList<Player> players = new ArrayList<>();

        Scanner scanner = new Scanner(System.in);
        System.out.print("Enter the number of players in the game: (2-4) ");
        while (!scanner.hasNextInt()) {
            System.out.println("That is not an integer. Try again:");
            scanner.next(); // Discard the invalid input
        }
        int numOfPlayers = scanner.nextInt();

        while (numOfPlayers < 2 || numOfPlayers > 4) {
            System.out.println("Invalid number of players. Please enter a number between 2 and 4.");
            System.out.print("Enter new number of players in the game: (2-4) ");
            numOfPlayers = scanner.nextInt();
        }

        for (int i = 1; i <= numOfPlayers; i++) {
            System.out.print("Enter name for Player " + i + ": ");
            String playerName = scanner.next();
            players.add(new Player(playerName));
        }

        Game game = new Game();
        game.gameStart(players);
        int maxTurns = 0;
        while (game.winner == null && maxTurns < 1000) {
            game.playTurn(scanner);
            maxTurns++;
        }

        scanner.close();
        if (game.winner != null) {
            System.out.println("Winner: " + game.winner.name);
        } else {
            System.out.println("No winner found (turn limit reached).");
        }
    }
}
//fin