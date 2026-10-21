import javafx.application.Application;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.stage.Stage;
import javafx.util.StringConverter;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Period;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class Main extends Application {
    private static final Path DATA = Path.of(System.getProperty("user.home"), ".calendarbaby", "accounts.dat");
    private final Map<String, Account> accounts = new java.util.concurrent.ConcurrentHashMap<>();
    private Stage stage;
    private Account current;
    private final ScheduledExecutorService reminderService = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "calendarbaby-reminders"); t.setDaemon(true); return t;
    });
    private static final String BG = "#f5f7fb", INK = "#23314d", PURPLE = "#7257d5";

    public static void main(String[] args) { launch(args); }

    @Override public void start(Stage stage) {
        this.stage = stage;
        stage.setTitle("Փոքրիկի օրացույց");
        stage.setMinWidth(760); stage.setMinHeight(600);
        loadAccounts(); showLogin(); stage.show();
        reminderService.scheduleAtFixedRate(this::sendDueReminders, 10, 30, TimeUnit.SECONDS);
        stage.setOnCloseRequest(e -> reminderService.shutdownNow());
    }

    private void showLogin() {
        VBox card = card(); card.setMaxWidth(440);
        Label title = title("Բարի գալուստ 🌷");
        Label sub = muted("Մուտք գործեք՝ փոքրիկի աճը հետևելու համար");
        TextField email = field("Էլեկտրոնային փոստ կամ մուտքանուն");
        PasswordField pass = password("Գաղտնաբառ");
        Button login = primary("Մուտք գործել");
        Hyperlink register = new Hyperlink("Դեռ գրանցված չե՞ք։ Գրանցվել");
        Label status = errorLabel();
        login.setOnAction(e -> {
            String name = email.getText().trim().toLowerCase();
            Account a = accounts.get(name);
            if (a == null || !verify(pass.getText(), a.salt, a.passwordHash)) status.setText("Սխալ մուտքանուն կամ գաղտնաբառ։");
            else if (!a.verified) status.setText("Հաշիվը հաստատված չէ։ Խնդրում ենք ավարտել գրանցումը։");
            else { current = a; showHome(); }
        });
        register.setOnAction(e -> showRegister());
        card.getChildren().addAll(title, sub, gap(14), email, pass, login, register, status);
        showCentered(card);
    }

    private void showRegister() {
        VBox card = card(); card.setMaxWidth(460);
        Label status = errorLabel();
        TextField email = field("Էլեկտրոնային փոստ");
        PasswordField pass = password("Գաղտնաբառ (առնվազն 6 նիշ)");
        PasswordField confirm = password("Կրկնել գաղտնաբառը");
        Button send = primary("Ստանալ հաստատման կոդ");
        Hyperlink back = new Hyperlink("Արդեն հաշիվ ունե՞ք։ Մուտք գործել"); back.setOnAction(e -> showLogin());
        send.setOnAction(e -> {
            String address = email.getText().trim().toLowerCase();
            if (!address.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")) { status.setText("Մուտքագրեք վավեր էլեկտրոնային փոստ։"); return; }
            if (pass.getText().length() < 6) { status.setText("Գաղտնաբառը պետք է ունենա առնվազն 6 նիշ։"); return; }
            if (!pass.getText().equals(confirm.getText())) { status.setText("Գաղտնաբառերը չեն համընկնում։"); return; }
            if (accounts.containsKey(address) && accounts.get(address).verified) { status.setText("Այս փոստով հաշիվ արդեն կա։"); return; }
            String code = String.format("%06d", new SecureRandom().nextInt(1_000_000));
            try {
                boolean sent = sendEmail(address, code);
                Account a = new Account(); a.email = address; a.salt = UUID.randomUUID().toString();
                a.passwordHash = hash(pass.getText(), a.salt); a.pendingCode = code;
                accounts.put(address, a); saveAccounts();
                showVerify(a, sent ? null : code);
            } catch (Exception ex) { status.setText("Նամակը չուղարկվեց. ստուգեք SMTP կարգավորումները։ " + ex.getMessage()); }
        });
        card.getChildren().addAll(title("Ստեղծել հաշիվ"), muted("Գրանցվեք էլեկտրոնային փոստով"), gap(12), email, pass, confirm, send, back, status);
        showCentered(card);
    }

    private void showVerify(Account account, String devCode) {
        VBox card = card(); card.setMaxWidth(440);
        TextField code = field("Վեցանիշ հաստատման կոդ"); Label status = errorLabel();
        Button verify = primary("Հաստատել հաշիվը");
        verify.setOnAction(e -> {
            if (!Objects.equals(code.getText().trim(), account.pendingCode)) { status.setText("Կոդը սխալ է։ Փորձեք կրկին։"); return; }
            account.verified = true; account.pendingCode = ""; saveAccounts(); current = account; showHome();
        });
        card.getChildren().addAll(title("Հաստատեք էլ․ փոստը"), muted("Հաստատման կոդն ուղարկվել է " + account.email), gap(12), code, verify);
        if (devCode != null) card.getChildren().add(new Label("SMTP-ը կարգավորված չէ։ Փորձնական կոդ՝ " + devCode));
        card.getChildren().add(status); showCentered(card);
    }

    private void showHome() {
        BorderPane root = new BorderPane(); root.setStyle("-fx-background-color: linear-gradient(to bottom right, #fff1f4, #f1efff, #e9faff);");
        HBox header = new HBox(12); header.setAlignment(Pos.CENTER_LEFT); header.setPadding(new Insets(16, 26, 16, 26));
        Label brand = new Label("🌷  Փոքրիկի օրացույց"); brand.setTextFill(Color.web(INK)); brand.setFont(Font.font(21));
        Region spacer = new Region(); HBox.setHgrow(spacer, Priority.ALWAYS);
        Label user = muted(current.email); Button logout = new Button("Ելք"); logout.setOnAction(e -> { current = null; showLogin(); });
        header.getChildren().addAll(brand, spacer, user, logout); root.setTop(header);
        TabPane tabs = new TabPane(); tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabs.getTabs().addAll(new Tab("Ամփոփում", dashboard()), new Tab("Աճի գրանցում", growth()), new Tab("Նշումներ", notes()));
        tabs.getTabs().add(new Tab("🔔 Հիշեցումներ", reminders()));
        root.setCenter(tabs); BorderPane.setMargin(tabs, new Insets(0, 24, 24, 24));
        stage.setScene(new Scene(root, 920, 700));
    }

    private VBox dashboard() {
        VBox box = new VBox(18); box.setPadding(new Insets(24));
        box.setStyle("-fx-background-color: linear-gradient(to bottom right, #fff0f5, #f4f0ff, #fff9e9);");
        Label head = title("Փոքրիկի տվյալները");
        TextField name = field("Անուն Ազգանուն"); name.setText(current.childName);
        DatePicker birth = new DatePicker(); birth.setPromptText("Ծննդյան ամսաթիվ"); birth.setMaxWidth(Double.MAX_VALUE);
        configureDatePicker(birth);
        if (current.birthDate != null && !current.birthDate.isBlank()) birth.setValue(LocalDate.parse(current.birthDate));
        Label age = new Label(); age.setFont(Font.font(20)); age.setTextFill(Color.web(PURPLE));
        age.setStyle("-fx-background-color: #f2eaff; -fx-background-radius: 14; -fx-padding: 16 20 16 20; -fx-font-weight: bold;");
        Runnable updateAge = () -> { if (birth.getValue() == null) age.setText("Նշեք ծննդյան ամսաթիվը՝ տարիքը հաշվարկելու համար։");
            else if (birth.getValue().isAfter(LocalDate.now())) age.setText("Ծննդյան ամսաթիվը չի կարող ապագայում լինել։");
            else age.setText("Փոքրիկը այսօր " + java.time.temporal.ChronoUnit.DAYS.between(birth.getValue(), LocalDate.now()) + " օրական է։  (" + Period.between(birth.getValue(), LocalDate.now()).getYears() + " տ․ " + Period.between(birth.getValue(), LocalDate.now()).getMonths() + " ամս․)"); };
        birth.valueProperty().addListener((o, old, val) -> updateAge.run()); updateAge.run();
        Button save = primary("Պահպանել տվյալները"); Label status = new Label();
        save.setOnAction(e -> {
            if (birth.getValue() != null && birth.getValue().isAfter(LocalDate.now())) { status.setText("Սխալ ամսաթիվ։"); return; }
            current.childName = name.getText().trim(); current.birthDate = birth.getValue() == null ? "" : birth.getValue().toString(); saveAccounts(); updateAge.run(); status.setText("Պահպանված է։");
        });
        VBox profile = card(); profile.getChildren().addAll(head, name, birth, age, save, status);
        Label hint = muted("Ամսական քաշն ու հասակը գրանցեք «Աճի գրանցում» բաժնում։ Փոփոխությունը կհաշվարկվի նախորդ գրանցման համեմատ։"); hint.setWrapText(true);
        box.getChildren().addAll(profile, hint); return box;
    }

    private VBox growth() {
        VBox box = new VBox(16); box.setPadding(new Insets(24));
        box.setStyle("-fx-background-color: linear-gradient(to bottom right, #eafff7, #edf5ff, #fff8e9);");
        DatePicker date = new DatePicker(LocalDate.now()); date.setMaxWidth(Double.MAX_VALUE);
        configureDatePicker(date);
        TextField weight = field("Քաշը գրեք ամբողջ կիլոգրամներով"); TextField height = field("Հասակ՝ սմ (օրինակ՝ 68)");
        Label status = errorLabel(); Button add = primary("Ավելացնել գրանցում");
        TableView<Growth> table = new TableView<>();
        TableColumn<Growth, String> d = new TableColumn<>("Ամսաթիվ"); d.setCellValueFactory(x -> x.getValue().dateProperty());
        TableColumn<Growth, String> w = new TableColumn<>("Քաշ (կգ)"); w.setCellValueFactory(x -> x.getValue().weightProperty());
        TableColumn<Growth, String> wd = new TableColumn<>("Քաշի փոփոխություն"); wd.setCellValueFactory(x -> x.getValue().weightChangeProperty());
        TableColumn<Growth, String> h = new TableColumn<>("Հասակ (սմ)"); h.setCellValueFactory(x -> x.getValue().heightProperty());
        TableColumn<Growth, String> hd = new TableColumn<>("Հասակի փոփոխություն"); hd.setCellValueFactory(x -> x.getValue().heightChangeProperty());
        table.getColumns().addAll(d,w,wd,h,hd); table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        Runnable refresh = () -> table.setItems(FXCollections.observableArrayList(current.growth)); refresh.run();
        add.setOnAction(e -> {
            try {
                if (date.getValue() == null) throw new IllegalArgumentException("Նշեք ամսաթիվը։");
                int kg = Integer.parseInt(weight.getText().trim()); double cm = Double.parseDouble(height.getText().replace(',', '.'));
                if (kg <= 0 || cm <= 0 || kg > 100 || cm > 250) throw new IllegalArgumentException("Քաշն ու հասակը պետք է լինեն դրական և իրատեսական։");
                if (current.growth.stream().anyMatch(g -> g.date.equals(date.getValue().toString()))) throw new IllegalArgumentException("Այս ամսաթվով գրանցում արդեն կա։");
                Growth previous = current.growth.stream().filter(g -> g.date.compareTo(date.getValue().toString()) < 0).max(Comparator.comparing(g -> g.date)).orElse(null);
                Growth entry = new Growth(); entry.date=date.getValue().toString(); entry.weight=kg; entry.height=cm;
                entry.weightChange=previous == null ? "—" : signedInteger(kg-previous.weight)+" կգ";
                entry.heightChange=previous == null ? "—" : signed(cm-previous.height)+" սմ";
                current.growth.add(entry); current.growth.sort(Comparator.comparing(g -> g.date)); saveAccounts(); refresh.run(); status.setText("Գրանցումը պահպանված է։");
            } catch (NumberFormatException ex) { status.setText("Քաշն ու հասակը լրացրեք թվերով։"); }
            catch (IllegalArgumentException ex) { status.setText(ex.getMessage()); }
        });
        VBox form=card(); form.getChildren().addAll(title("Ամսական չափումներ"),date,weight,height,add,status);
        box.getChildren().addAll(form, title("Գրանցումների պատմություն"), table); VBox.setVgrow(table, Priority.ALWAYS); return box;
    }

    private VBox notes() {
        VBox box = new VBox(14); box.setPadding(new Insets(24));
        box.setStyle("-fx-background-color: linear-gradient(to bottom right, #fff1e9, #fff1f7, #f4efff);");
        TextArea input = new TextArea(); input.setPromptText("Գրեք ձեր նշումը…"); input.setPrefRowCount(3); input.setWrapText(true);
        Button add = primary("Ավելացնել նշում"); VBox list = new VBox(10); Label status = errorLabel();
        Runnable[] refresh = new Runnable[1]; refresh[0] = () -> { list.getChildren().clear(); current.notes.sort(Comparator.comparing((Note n)->n.date).reversed());
            for (Note n : current.notes) { VBox item=card(); Label dt=muted(n.date); Label body=new Label(n.text); body.setWrapText(true);
                Button del=new Button("Ջնջել"); del.setOnAction(e->{current.notes.remove(n);saveAccounts();refresh[0].run();});
                HBox row=new HBox(dt,new Region(),del); HBox.setHgrow(row.getChildren().get(1),Priority.ALWAYS); item.getChildren().addAll(row,body); list.getChildren().add(item); }
        }; refresh[0].run();
        add.setOnAction(e->{String value=input.getText().trim(); if(value.isEmpty()){status.setText("Նշումը դատարկ է։");return;} Note n=new Note();n.date=LocalDate.now().toString();n.text=value;current.notes.add(n);input.clear();saveAccounts();refresh[0].run();status.setText("");});
        box.getChildren().addAll(title("Նշումներ"),input,add,status,list); return box;
    }

    private VBox reminders() {
        VBox page = new VBox(16); page.setPadding(new Insets(24));
        ComboBox<String> kind = new ComboBox<>(FXCollections.observableArrayList("🍼 Կերակրում", "😴 Քուն", "🏥 Պոլիկլինիկայի այց", "💉 Պատվաստում"));
        kind.getSelectionModel().selectFirst(); kind.setMaxWidth(Double.MAX_VALUE);
        DatePicker date = new DatePicker(LocalDate.now()); date.setMaxWidth(Double.MAX_VALUE);
        configureDatePicker(date);
        TextField time = field("Ժամը՝ 09:30"); time.setText("09:00");
        TextField details = field("Լրացուցիչ մանրամասներ (ըստ ցանկության)");
        Label info = muted("Հիշեցումը կուղարկվի " + current.email + " հասցեին։ Հավելվածը պետք է բաց լինի, իսկ SMTP-ն կարգավորված։"); info.setWrapText(true);
        Label status = errorLabel(); Button add = primary("Հիշեցում ավելացնել"); VBox list = new VBox(10);
        Runnable[] refresh = new Runnable[1];
        refresh[0] = () -> {
            list.getChildren().clear();
            current.reminders.sort(Comparator.comparing(r -> r.dateTime));
            for (Reminder r : current.reminders) {
                HBox row = new HBox(12); row.setAlignment(Pos.CENTER_LEFT); row.setPadding(new Insets(14));
                row.setStyle("-fx-background-color:" + (r.sent ? "#e9f8ef" : "#fff3e8") + ";-fx-background-radius:14;");
                Label text = new Label(r.kind + "  •  " + r.dateTime.replace('T', ' ') + (r.details.isBlank() ? "" : "\n" + r.details) + (r.sent ? "\nՈւղարկված է" : "\nՍպասման մեջ է"));
                text.setWrapText(true); Region gap = new Region(); HBox.setHgrow(gap, Priority.ALWAYS);
                Button remove = new Button("Ջնջել"); remove.setOnAction(e -> { current.reminders.remove(r); saveAccounts(); refresh[0].run(); });
                row.getChildren().addAll(text, gap, remove); list.getChildren().add(row);
            }
        };
        refresh[0].run();
        add.setOnAction(e -> {
            try {
                if (date.getValue() == null) throw new IllegalArgumentException("Ընտրեք ամսաթիվը։");
                LocalDateTime when = LocalDateTime.of(date.getValue(), java.time.LocalTime.parse(time.getText().trim()));
                if (when.isBefore(LocalDateTime.now().minusSeconds(30))) throw new IllegalArgumentException("Ընտրեք ապագա ժամը։");
                Reminder r = new Reminder(); r.kind = kind.getValue(); r.dateTime = when.toString(); r.details = details.getText().trim();
                current.reminders.add(r); saveAccounts(); refresh[0].run(); details.clear(); status.setText("Հիշեցումը պահպանվեց։");
            } catch (java.time.DateTimeException ex) { status.setText("Ժամը լրացրեք HH:mm ձևաչափով, օրինակ՝ 09:30։"); }
            catch (IllegalArgumentException ex) { status.setText(ex.getMessage()); }
        });
        VBox form = card(); form.setStyle("-fx-background-color: linear-gradient(to right, #fff0f4, #f3efff);-fx-background-radius:18;");
        form.getChildren().addAll(title("🔔 Խնամքի հիշեցումներ"), kind, date, time, details, add, info, status);
        page.getChildren().addAll(form, title("Ձեր հիշեցումները"), list); return page;
    }

    private void sendDueReminders() {
        for (Account account : accounts.values()) {
            for (Reminder reminder : new ArrayList<>(account.reminders)) {
                synchronized (reminder) {
                    if (reminder.sent || LocalDateTime.parse(reminder.dateTime).isAfter(LocalDateTime.now())) continue;
                    try {
                        String body = "Հիշեցում՝ " + reminder.kind + "\nԺամը՝ " + reminder.dateTime.replace('T', ' ') +
                                (reminder.details.isBlank() ? "" : "\nՄանրամասներ՝ " + reminder.details);
                        if (sendEmailMessage(account.email, "Փոքրիկի խնամքի հիշեցում", body)) {
                            reminder.sent = true; saveAccounts();
                            Platform.runLater(() -> { if (current == account) refreshReminderTab(); });
                        }
                    } catch (Exception ex) { System.err.println("Հիշեցումը չուղարկվեց․ " + ex.getMessage()); }
                }
            }
        }
    }

    private void refreshReminderTab() {
        if (stage.getScene() == null || !(stage.getScene().getRoot() instanceof BorderPane root) || !(root.getCenter() instanceof TabPane tabs)) return;
        for (Tab tab : tabs.getTabs()) if (tab.getText().contains("Հիշեցումներ")) {
            int index = tabs.getTabs().indexOf(tab); tabs.getTabs().set(index, new Tab(tab.getText(), reminders())); return;
        }
    }

    private boolean sendEmail(String to, String code) throws Exception {
        return sendEmailMessage(to, "Հաստատման կոդ", "Ձեր հաստատման կոդն է՝ " + code);
    }

    private boolean sendEmailMessage(String to, String subject, String body) throws Exception {
        String host=System.getenv("CALENDARBABY_SMTP_HOST"); String user=System.getenv("CALENDARBABY_SMTP_USER"); String pass=System.getenv("CALENDARBABY_SMTP_PASSWORD");
        if(host==null||user==null||pass==null) return false;
        int port=Integer.parseInt(Optional.ofNullable(System.getenv("CALENDARBABY_SMTP_PORT")).orElse("465"));
        try(SSLSocket socket=(SSLSocket)SSLSocketFactory.getDefault().createSocket(host,port)) {
            socket.setSoTimeout(10000); BufferedReader in=new BufferedReader(new InputStreamReader(socket.getInputStream(),StandardCharsets.UTF_8));
            BufferedWriter out=new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(),StandardCharsets.UTF_8));
            expect(in,"220"); command(out,in,"EHLO calendarbaby.local","250"); command(out,in,"AUTH LOGIN","334");
            command(out,in,Base64.getEncoder().encodeToString(user.getBytes(StandardCharsets.UTF_8)),"334");
            command(out,in,Base64.getEncoder().encodeToString(pass.getBytes(StandardCharsets.UTF_8)),"235");
            command(out,in,"MAIL FROM:<"+user+">","250"); command(out,in,"RCPT TO:<"+to+">","250","251"); command(out,in,"DATA","354");
            out.write("From: CalendarBaby <"+user+">\r\nTo: "+to+"\r\nSubject: =?UTF-8?B?"+Base64.getEncoder().encodeToString(subject.getBytes(StandardCharsets.UTF_8))+"?=\r\nMIME-Version: 1.0\r\nContent-Type: text/plain; charset=UTF-8\r\n\r\n"+body+"\r\n.\r\n");out.flush();expect(in,"250");command(out,in,"QUIT","221");return true;
        }
    }
    private void command(BufferedWriter out,BufferedReader in,String text,String... expected)throws IOException {out.write(text+"\r\n");out.flush();String reply=readReply(in);for(String code:expected)if(reply.startsWith(code))return;throw new IOException("SMTP պատասխան՝ "+reply);}
    private void expect(BufferedReader in,String code)throws IOException {String reply=readReply(in);if(!reply.startsWith(code))throw new IOException("SMTP պատասխան՝ "+reply);}
    private String readReply(BufferedReader in)throws IOException {String line=in.readLine();if(line==null)throw new IOException("SMTP կապը փակվեց");StringBuilder r=new StringBuilder(line);while(line.length()>3&&line.charAt(3)=='-'){line=in.readLine();if(line==null)break;r.append(" ").append(line);}return r.toString();}

    private String hash(String password,String salt) {try {var spec=new javax.crypto.spec.PBEKeySpec(password.toCharArray(),salt.getBytes(StandardCharsets.UTF_8),120000,256);return Base64.getEncoder().encodeToString(javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded());}catch(Exception e){throw new IllegalStateException(e);}}
    private boolean verify(String pass,String salt,String expected){return MessageDigest.isEqual(hash(pass,salt).getBytes(StandardCharsets.UTF_8),expected.getBytes(StandardCharsets.UTF_8));}
    private void loadAccounts(){if(!Files.exists(DATA))return;Properties p=new Properties();try(InputStream in=Files.newInputStream(DATA)){p.load(in);int count=Integer.parseInt(p.getProperty("count","0"));for(int i=0;i<count;i++){String pre="account."+i+".";Account a=new Account();a.email=p.getProperty(pre+"email","");a.salt=p.getProperty(pre+"salt","");a.passwordHash=p.getProperty(pre+"hash","");a.verified=Boolean.parseBoolean(p.getProperty(pre+"verified","false"));a.pendingCode=p.getProperty(pre+"code","");a.childName=p.getProperty(pre+"child","");a.birthDate=p.getProperty(pre+"birth","");int gc=Integer.parseInt(p.getProperty(pre+"growth.count","0"));for(int j=0;j<gc;j++){String q=pre+"growth."+j+".";Growth g=new Growth();g.date=p.getProperty(q+"date");g.weight=(int)Double.parseDouble(p.getProperty(q+"weight"));g.height=Double.parseDouble(p.getProperty(q+"height"));g.weightChange=p.getProperty(q+"weightChange","");g.heightChange=p.getProperty(q+"heightChange","");a.growth.add(g);}int nc=Integer.parseInt(p.getProperty(pre+"notes.count","0"));for(int j=0;j<nc;j++){Note n=new Note();n.date=p.getProperty(pre+"note."+j+".date","");n.text=p.getProperty(pre+"note."+j+".text","");a.notes.add(n);}loadReminders(a,p,pre);accounts.put(a.email,a);}}catch(Exception e){System.err.println("Չհաջողվեց կարդալ պահված տվյալները։ "+e.getMessage());}}
    private void loadReminders(Account a, Properties p, String pre){int count=Integer.parseInt(p.getProperty(pre+"reminders.count","0"));for(int j=0;j<count;j++){String q=pre+"reminder."+j+".";Reminder r=new Reminder();r.kind=p.getProperty(q+"kind","");r.dateTime=p.getProperty(q+"dateTime","");r.details=p.getProperty(q+"details","");r.sent=Boolean.parseBoolean(p.getProperty(q+"sent","false"));if(!r.dateTime.isBlank())a.reminders.add(r);}}
    private void saveAccounts(){Properties p=new Properties();p.setProperty("count",String.valueOf(accounts.size()));int i=0;for(Account a:accounts.values()){String pre="account."+i++ +".";p.setProperty(pre+"email",a.email);p.setProperty(pre+"salt",a.salt);p.setProperty(pre+"hash",a.passwordHash);p.setProperty(pre+"verified",String.valueOf(a.verified));p.setProperty(pre+"code",a.pendingCode);p.setProperty(pre+"child",a.childName);p.setProperty(pre+"birth",a.birthDate);p.setProperty(pre+"growth.count",String.valueOf(a.growth.size()));for(int j=0;j<a.growth.size();j++){Growth g=a.growth.get(j);String q=pre+"growth."+j+".";p.setProperty(q+"date",g.date);p.setProperty(q+"weight",String.valueOf(g.weight));p.setProperty(q+"height",String.valueOf(g.height));p.setProperty(q+"weightChange",g.weightChange);p.setProperty(q+"heightChange",g.heightChange);}p.setProperty(pre+"notes.count",String.valueOf(a.notes.size()));for(int j=0;j<a.notes.size();j++){Note n=a.notes.get(j);p.setProperty(pre+"note."+j+".date",n.date);p.setProperty(pre+"note."+j+".text",n.text);}p.setProperty(pre+"reminders.count",String.valueOf(a.reminders.size()));for(int j=0;j<a.reminders.size();j++){Reminder r=a.reminders.get(j);String q=pre+"reminder."+j+".";p.setProperty(q+"kind",r.kind);p.setProperty(q+"dateTime",r.dateTime);p.setProperty(q+"details",r.details);p.setProperty(q+"sent",String.valueOf(r.sent));}}try{Files.createDirectories(DATA.getParent());try(OutputStream out=Files.newOutputStream(DATA)){p.store(out,"CalendarBaby local accounts");}}catch(IOException e){new Alert(Alert.AlertType.ERROR,"Տվյալները չպահպանվեցին։ "+e.getMessage()).showAndWait();}}

    private VBox card(){VBox v=new VBox(12);v.setPadding(new Insets(22));v.setStyle("-fx-background-color:white;-fx-background-radius:16;-fx-effect:dropshadow(gaussian, #24324d16, 14, 0.12, 0, 4);");return v;}
    private TextField field(String prompt){TextField f=new TextField();f.setPromptText(prompt);f.setPrefHeight(42);return f;}
    private PasswordField password(String prompt){PasswordField f=new PasswordField();f.setPromptText(prompt);f.setPrefHeight(42);return f;}
    private Button primary(String text){Button b=new Button(text);b.setMaxWidth(Double.MAX_VALUE);b.setPrefHeight(42);b.setStyle("-fx-background-color:"+PURPLE+";-fx-text-fill:white;-fx-font-weight:bold;-fx-background-radius:9;");return b;}
    private Label title(String s){Label l=new Label(s);l.setFont(Font.font(23));l.setTextFill(Color.web(INK));return l;}
    private Label muted(String s){Label l=new Label(s);l.setTextFill(Color.web("#7b8498"));return l;}
    private Label errorLabel(){Label l=new Label();l.setTextFill(Color.web("#bd4560"));l.setWrapText(true);return l;}
    private Region gap(double h){Region r=new Region();r.setMinHeight(h);return r;}
    private void showCentered(VBox card){StackPane root=new StackPane(card);root.setPadding(new Insets(24));root.setStyle("-fx-background-color: linear-gradient(to bottom right, #ffeef3, #eeeaff, #e8faff);");stage.setScene(new Scene(root,850,650));}
    private String signed(double n){return (n>0?"+":"")+String.format(Locale.US,"%.1f",n);}
    private String signedInteger(int n){return (n>0?"+":"")+n;}
    private void configureDatePicker(DatePicker picker) {
        DateTimeFormatter format = DateTimeFormatter.ofPattern("dd.MM.uuuu");
        picker.setConverter(new StringConverter<>() {
            @Override public String toString(LocalDate date) { return date == null ? "" : format.format(date); }
            @Override public LocalDate fromString(String text) {
                if (text == null || text.isBlank()) return null;
                try { return LocalDate.parse(text.trim(), format); }
                catch (java.time.format.DateTimeParseException ignored) {
                    try { return LocalDate.parse(text.trim()); }
                    catch (java.time.format.DateTimeParseException invalid) { return null; }
                }
            }
        });
        picker.setPromptText("օր. 20.10.2026");
    }

    private static class Account {String email="",salt="",passwordHash="",pendingCode="",childName="",birthDate="";boolean verified;List<Growth> growth=new ArrayList<>();List<Note> notes=new ArrayList<>();List<Reminder> reminders=new ArrayList<>();}
    private static class Growth {String date,weightChange="",heightChange="";int weight;double height; javafx.beans.property.StringProperty dateProperty(){return new javafx.beans.property.SimpleStringProperty(date);} javafx.beans.property.StringProperty weightProperty(){return new javafx.beans.property.SimpleStringProperty(String.valueOf(weight));} javafx.beans.property.StringProperty heightProperty(){return new javafx.beans.property.SimpleStringProperty(String.format(Locale.US,"%.1f",height));} javafx.beans.property.StringProperty weightChangeProperty(){return new javafx.beans.property.SimpleStringProperty(weightChange);} javafx.beans.property.StringProperty heightChangeProperty(){return new javafx.beans.property.SimpleStringProperty(heightChange);} }
    private static class Note {String date,text;}
    private static class Reminder {String kind="",dateTime="",details="";boolean sent;}
}
