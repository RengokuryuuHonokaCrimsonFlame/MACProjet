//Sources : https://monsterdeveloper.gitbooks.io/writing-telegram-bots-on-java/content/
import com.mongodb.client.FindIterable;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.neo4j.driver.v1.Record;
import org.neo4j.driver.v1.StatementResult;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.lang.StrictMath.toIntExact;

public class Bot extends TelegramLongPollingBot {
    private HashMap<Long, Integer> addRecipeStatus = new HashMap<>();
    private HashMap<Long, List<List<String>>> addRecipeData = new HashMap<>();

    public void onUpdateReceived(Update update) {
        if (update.hasMessage() && update.getMessage().hasText()) {
            long userId = update.getMessage().getChat().getId();
            String userFirstNname = update.getMessage().getChat().getFirstName();
            String userLastName = update.getMessage().getChat().getLastName();
            String userUsername = update.getMessage().getChat().getUserName();
            String message_text = update.getMessage().getText();
            MongoDBDAO.getInstance().check(userFirstNname, userLastName, toIntExact(userId), userUsername);
            long chat_id = update.getMessage().getChatId();
            SendMessage message = null;

            if (message_text.equals("/reset")) {
                addRecipeData.remove(userId);
                addRecipeStatus.remove(userId);
                message = new SendMessage( ).setChatId(chat_id).setText("L'ajout de recette a été avorté");
            } else if (addRecipeStatus.containsKey(userId)) {
                switch (addRecipeStatus.get(userId)) {
                    case 0:
                        newRecipeList(userId, message_text);
                        message = new SendMessage().setChatId(chat_id).setText("Veuillez spécifier les ustensiles (" +
                                "séparés par une virgule)\n Exemple: mixer, micro-ondes, spatule");
                        break;
                    case 1:
                        newRecipeList(userId, message_text);
                        message = new SendMessage().setChatId(chat_id).setText("Veuillez spécifier le temps de" +
                                " préparation\n Exemple: 1h 30m");
                        break;
                    case 2:
                        if (newRecipeRegex(userId, message_text, "([0-9]+m|[0-9]+h|[0-9]+h [0-9]+m)")) {
                            message = new SendMessage().setChatId(chat_id).setText("Format incorrect\n Exemple: 1h 30m");
                        } else {
                            message = new SendMessage().setChatId(chat_id).setText("Veuillez spécifier le nombre de" +
                                    " calories\n Exemple: 250kcal");
                        }
                        break;
                    case 3:
                        if (newRecipeRegex(userId, message_text, "([0-9]+(kcal)?)")) {
                            message = new SendMessage().setChatId(chat_id).setText("Format incorrect\n Exemple: 250kcal");
                        } else {
                            message = new SendMessage().setChatId(chat_id).setText("Veuillez décrire la recette");
                        }
                        break;
                    case 4:
                        newRecipeSinglePhrase(userId, message_text);
                        message = new SendMessage().setChatId(chat_id).setText("Vous pouvez aussi spécifier d'autres " +
                                "tags\n Exemple: biscuit, gateau, pâtisserie japonaise, etc...");
                        InlineKeyboardMarkup markupInline = new InlineKeyboardMarkup();
                        List<List<InlineKeyboardButton>> rowsInline = new ArrayList<>();
                        List<InlineKeyboardButton> rowInline = new ArrayList<>();
                        rowInline.add(new InlineKeyboardButton().setText("Non merci").setCallbackData("nothankyou " + userId));
                        rowsInline.add(rowInline);
                        markupInline.setKeyboard(rowsInline);
                        message.setReplyMarkup(markupInline);
                        break;
                    case 5:
                        newRecipeList(userId, message_text);
                        addNewRecipe(userId);
                        message = new SendMessage( ).setChatId(chat_id).setText("La recette a été ajoutée");
                        break;
                }
            } else if (message_text.startsWith("/newrecipe ")) {
                newRecipeSinglePhrase(userId, message_text.substring(11));
                message = new SendMessage().setChatId(chat_id).setText("Veuillez spécifier les ingrédients avec " +
                                "leur quantité (séparer les quantités des ingrédients avec '/')\n " +
                                "Exemple: sucre/250g, farine/150g, eau/2 tasses, confiture d'abricot/1 pot");
            } else if (message_text.equals("/random")) {
                Document random = MongoDBDAO.getInstance().getRandomRecipe();
                String id = random.get("_id").toString();
                message = new SendMessage().setChatId(chat_id).setText(getRecipeById(id));
                InlineKeyboardMarkup markupInline = new InlineKeyboardMarkup();
                List<List<InlineKeyboardButton>> rowsInline = new ArrayList<>();
                List<InlineKeyboardButton> rowInline = new ArrayList<>();
                rowInline.add(new InlineKeyboardButton().setText("Like this recipe").setCallbackData("Like "
                        + userId  + " _"  + id));
                rowsInline.add(rowInline);
                markupInline.setKeyboard(rowsInline);
                message.setReplyMarkup(markupInline);
            } else if (message_text.startsWith("/getrecipe ")) {
                message = new SendMessage( ).setChatId(chat_id).setText(
                        getRecipeById(message_text.substring(11)));
                InlineKeyboardMarkup markupInline = new InlineKeyboardMarkup();
                List<List<InlineKeyboardButton>> rowsInline = new ArrayList<>();
                List<InlineKeyboardButton> rowInline = new ArrayList<>();
                rowInline.add(new InlineKeyboardButton().setText("Like this recipe").setCallbackData("Like "
                        + userId  + " _"  + message_text.substring(11)));
                rowsInline.add(rowInline);
                markupInline.setKeyboard(rowsInline);
                message.setReplyMarkup(markupInline);
            } else if (message_text.startsWith("/recipesbyingredients ")) {
                List<String> ingredients = Arrays.asList(message_text.substring(22).replaceAll(" ", "").split(","));
                message = new SendMessage().setChatId(chat_id).setText("With " + message_text.substring(22) + "\n" +
                        getRecipesByIngredients(ingredients));
                InlineKeyboardMarkup markupInline = new InlineKeyboardMarkup();
                List<List<InlineKeyboardButton>> rowsInline = new ArrayList<>();
                List<InlineKeyboardButton> rowInline = new ArrayList<>();
                rowInline.add(new InlineKeyboardButton().setText("Like these ingredients").setCallbackData(
                        "Like " + userId  + " " + message_text.substring(22)));
                rowsInline.add(rowInline);
                markupInline.setKeyboard(rowsInline);
                message.setReplyMarkup(markupInline);
            } else if (message_text.startsWith("/recipesbyuser ")) {
                message = new SendMessage( ).setChatId(chat_id).setText(getRecipesByUser(message_text.substring(15)));
                InlineKeyboardMarkup markupInline = new InlineKeyboardMarkup();
                List<List<InlineKeyboardButton>> rowsInline = new ArrayList<>();
                List<InlineKeyboardButton> rowInline = new ArrayList<>();
                rowInline.add(new InlineKeyboardButton().setText("Like this user").setCallbackData("Like " + userId  +
                        " _" + message_text.substring(15)));
                rowsInline.add(rowInline);
                markupInline.setKeyboard(rowsInline);
                message.setReplyMarkup(markupInline);
            } else if (message_text.startsWith("/recipesbycalories ")) {
                String calories = message_text.substring(18).replaceAll(" ", "");
                message = new SendMessage().setChatId(chat_id).setText("With " + message_text.substring(17) + "\n" +
                        getRecipesByCalories(calories));
            } else if (message_text.startsWith("/recipesbytools ")) {
                List<String> tools = Arrays.asList(message_text.substring(16).replaceAll(" ", "").split(","));
                message = new SendMessage().setChatId(chat_id).setText("With " + message_text.substring(16) + "\n" +
                        getRecipesByTools(tools));
                InlineKeyboardMarkup markupInline = new InlineKeyboardMarkup();
                List<List<InlineKeyboardButton>> rowsInline = new ArrayList<>();
                List<InlineKeyboardButton> rowInline = new ArrayList<>();
                rowInline.add(new InlineKeyboardButton().setText("Like these tools").setCallbackData(
                        "Like " + userId  + " " + message_text.substring(16)));
                rowsInline.add(rowInline);
                markupInline.setKeyboard(rowsInline);
                message.setReplyMarkup(markupInline);
            }else if (message_text.startsWith("/recipesbytime ")) {
                String str = message_text.substring(15);
                String test = getRecipesByTime(str);
                message = new SendMessage( ).setChatId(chat_id).setText(test);
            }else if (message_text.startsWith("/userscooking ")) {
                message = new SendMessage( ).setChatId(chat_id).setText(getUserCooking(message_text.substring(14)));
            }else if (message_text.equals("/recommandations")) {
                message = new SendMessage( ).setChatId(chat_id).setText(getRecommandation(userId));
            }else if (message_text.equals("/help")) {
                message = new SendMessage().setChatId(chat_id).setText(
                    "/newrecipe [nom] -> Démarre la création de la recette [nom]\n" +
                    "/reset -> Met fin au processus de création d'une recette\n" +
                    "/random -> Affiche une recette aléatoire\n" +
                    "/getrecipe [id] -> Affiche la recette ayant pour id [id]\n" +
                    "/recipesbyingredients [i1, i2, ...] -> Affiche les recettes ayant pour ingrédients [i1, i2, ...]\n" +
                    "/recipesbyuser [utilisateur] -> Affiche les recettes de l'utilisateur [utilisateur]\n" +
                    "/recipesbycalories [calories] -> Affiche les recettes ayant moins de [calories] calories\n" +
                    "/recipesbytools [t1, t2, ...] -> Affiche les recettes ayant pour ustensiles [t1, t2, ...]\n" +
                    "/recipesbytime [temps] -> Affiche les recettes prenant [temps] à réaliser à 5 minutes près\n" +
                    "/userscooking [id]-> Affiche les utilisateurs ayant fait des recettes similaires.\n" +
                    "/recommandations -> Propose des recettes sur la base de celles consultées jusqu'à présent\n" +
                    "/help -> Affiche la liste des commandes disponibles");
            } else {
                message = new SendMessage().setChatId(chat_id).setText("Je ne connais pas cette commande.\n Pour plus d'infos : /help");
            }
            try {
                if (message != null) {
                    execute(message);
                }
            } catch (TelegramApiException e) {
                e.printStackTrace();
            }
        } else if (update.hasCallbackQuery()) {
            //A utiliser pour rajouter des like et nothankyou
            String call_data = update.getCallbackQuery().getData();
            //long message_id = update.getCallbackQuery().getMessage().getMessageId();
            long chat_id = update.getCallbackQuery().getMessage().getChatId();
            if (call_data.startsWith("nothankyou ")){
                String userId = call_data.substring(11);
                addNewRecipe(Long.parseLong(userId));
                SendMessage message = new SendMessage( ).setChatId(chat_id).setText("La recette a été ajoutée");
                try {
                    execute(message);
                } catch (TelegramApiException e) {
                    e.printStackTrace();
                }
            } else if (call_data.startsWith("Like ")) {
               List<String> liked = Arrays.asList(call_data.substring(5).split(" "));
               Neo4jDAO.getInstance().addLike(liked);
            }
        }
    }

    public String getBotUsername() {
        return "SyugarDaddyBot";
    }

    public String getBotToken() {
        return "1057276327:AAHJLLnJ-4kCdQWe5hCnFtJB5V8ZGf7FTwY";
    }

    private void newRecipeSinglePhrase(long id, String recipeName) {
        int state = addRecipeStatus.get(id) == null ? -1 : addRecipeStatus.get(id);
        List<List<String>> newRecipe = new ArrayList<>();
        List<String> name = new ArrayList<>();
        name.add(recipeName);
        if (addRecipeStatus.get(id) != null) {
            newRecipe = addRecipeData.get(id);
        }
        newRecipe.add(name);
        addRecipeData.put(id, newRecipe);
        addRecipeStatus.put(id, ++state);

    }

    private void newRecipeList(long id, String list) {
        int state = addRecipeStatus.get(id);
        List<String> myList = Arrays.asList(list.replace("[^a-zA-Z/]+", "")
                .toLowerCase().replace(" ", "").split(","));
        List<List<String>> newRecipe = addRecipeData.get(id);
        newRecipe.add(myList);
        addRecipeData.put(id, newRecipe);
        addRecipeStatus.put(id, ++state);
    }

    private boolean newRecipeRegex(long id, String list, String regex) {
        int state = addRecipeStatus.get(id);
        Pattern pat = Pattern.compile(regex);
        Matcher match = pat.matcher(list);
        if (!match.find()) {
            return true;
        }
        List<String> myList = Collections.singletonList(list.toLowerCase());
        List<List<String>> newRecipe = addRecipeData.get(id);
        newRecipe.add(myList);
        addRecipeData.put(id, newRecipe);
        addRecipeStatus.put(id, ++state);
        return false;
    }

    private void addNewRecipe(long id) {
        //Nom de la recette, ingrédients, ustenciles, temps, kcal, description, sous-catégorie
        String name = addRecipeData.get(id).get(0).get(0);
        String description = addRecipeData.get(id).get(5).get(0);
        String time = addRecipeData.get(id).get(3).get(0);
        String kcal = addRecipeData.get(id).get(4).get(0);
        List<String> ingredients = addRecipeData.get(id).get(1);
        List<String> ustenciles = addRecipeData.get(id).get(2);
        List<String> subcategories;
        if (addRecipeData.get(id).size() > 6) {
            subcategories = addRecipeData.get(id).get(6);
        } else {
            subcategories = new LinkedList<>();
        }
        ObjectId recipeId = MongoDBDAO.getInstance().addRecipe(name, description, convertTimeToInt(time), getIntFromKcal(kcal));
        Neo4jDAO.getInstance().addRecipe(id, recipeId.toString(), ingredients, ustenciles, subcategories);
        addRecipeData.remove(id);
        addRecipeStatus.remove(id);
    }

    private int convertTimeToInt(String time){
        String[] parts = time.split(" ");
        if(parts.length > 1){
            return Integer.parseInt(parts[0].substring(0, parts[0].length() -1)) * 60 + Integer.parseInt(parts[1].substring(0, parts[1].length() -1));
        }
        if(parts[0].endsWith("m")){
            return Integer.parseInt(parts[0].substring(0, parts[0].length() -1));
        }
        return Integer.parseInt(parts[0].substring(0, parts[0].length() -1)) * 60;
    }

    private String convertIntToTime(int i){
        String time = "";
        int heure = i /60;
        int minute = i - heure;
        if(heure != 0){
            time += heure + "h ";
        }
        if(minute != 0){
            time += minute + "m";
        }
        return time;
    }

    private int getIntFromKcal(String kcal){
        if(kcal.endsWith("kcal")){
            kcal = kcal.substring(0, kcal.length() -4);
        }
        return Integer.parseInt(kcal);
    }

    private String getRecipeById(String recipeId){
        Document documentation = MongoDBDAO.getInstance().findRecipe(recipeId);
        StatementResult ingredients = Neo4jDAO.getInstance().getRecipeParts(recipeId, "IN", ",rel.quantite");
        StatementResult tools = Neo4jDAO.getInstance().getRecipeParts(recipeId, "USEFULL", "");
        StatementResult user = Neo4jDAO.getInstance().getRecipeParts(recipeId, "PROPOSED", ",rel.date");
        StringBuilder recipe = new StringBuilder(documentation.get("name").toString() + "\nIngrédients : \n");
        while (ingredients.hasNext()) {
            Record record = ingredients.next();
            recipe.append(" - ").append(record.get(0).asString()).append(" ").append(record.get(1).asString()).append("\n");
        }

        recipe.append("\nUstensiles utilisés : \n");
        while (tools.hasNext()) {
            Record record = tools.next();
            recipe.append(" - ").append(record.get(0).asString()).append("\n");
        }

        recipe.append("Temps de préparation : ").append(convertIntToTime((int)documentation.get("time"))).append("\n")
              .append("Calories : ").append(documentation.get("kcal").toString()).append("kcal\n");
        recipe.append("Marche à suivre\n").append(documentation.get("description")).append("\n\n");

        while (user.hasNext()) {
            Record record = user.next();
            recipe.append("Proposée par n°").append(record.get(0).asString()).append(" le ").append(record.get(1)).append("\n");
        }
        return recipe.toString();
    }

    private String getRecipesByUser(String user) {
        StringBuilder result = new StringBuilder();
        StatementResult str = Neo4jDAO.getInstance().getRecipesByUser(user);
        return getRecipesNeo(result, str);
    }

    private String getRecipesByIngredients(List<String> ingredients) {
        StringBuilder result = new StringBuilder();
        StatementResult str = Neo4jDAO.getInstance().getRecipesByIngredients(ingredients);
        return getRecipesNeo(result, str);
    }

    private String getRecipesByTools(List<String> tools) {
        StringBuilder result = new StringBuilder();
        StatementResult str = Neo4jDAO.getInstance().getRecipesByTools(tools);
        return getRecipesNeo(result, str);
    }

    private String getRecipesByCalories(String calories) {
        StringBuilder result = new StringBuilder();
        FindIterable<Document> ld = MongoDBDAO.getInstance().findDocumentByCalories(getIntFromKcal(calories));
        return getRecipesMongo(result, ld);
    }

    private String getRecipesByTime(String time) {
        FindIterable<Document> ld = MongoDBDAO.getInstance().findDocumentByTime(convertTimeToInt(time));
        StringBuilder result = new StringBuilder();
        return getRecipesMongo(result, ld);
    }

    private String getRecipesNeo(StringBuilder result, StatementResult str) {
        while (str.hasNext()) {
            Record record = str.next();
            String recipeId = record.get(0).asString().substring(1);
            Document recipe = MongoDBDAO.getInstance().findRecipe(recipeId);
            result.append(recipeId).append("\t\t").append(recipe.get("name")).append("\n");
        }
        if (result.toString().equals("")) {
            result.append("No result found");
        } else {
            result.insert(0, "id \t\t\t\t\t\t\t\t\t nom\n");
        }
        return result.toString();
    }
    private String getRecipesMongo(StringBuilder result, FindIterable<Document> ld) {
        for (Document recipe : ld) {
            result.append(recipe.get("_id")).append("\t\t").append(recipe.get("name")).append("\n");
        }
        if(result.toString().equals("")){
            result.append("No result found");
        }else{
            result.insert(0, "id \t\t\t\t\t\t\t\t\t nom\n");
        }
        return result.toString();
    }

    private String getUserCooking(String recipeId){
        StringBuilder result = new StringBuilder("Utilisateurs ayant entré une recette similaire : \n");
        StatementResult users = Neo4jDAO.getInstance().getSimilarUsers(recipeId);
        while (users.hasNext())
        {
            Record record = users.next();
            String userId = record.get(0).asString().substring(1);
            Document user = MongoDBDAO.getInstance().findUser(userId);
            result.append(userId).append("\t\t").append(user.get("username")).append("\n");
        }
        return result.toString();
    }

    private String getRecommandation(long userId){
        StringBuilder result = new StringBuilder("Recommandations : \n");
        StatementResult recommandations = Neo4jDAO.getInstance().getRecommandation(userId);
        while (recommandations.hasNext())
        {
            Record record = recommandations.next();
            String recommandation = record.get(0).asString().substring(1);
            Document recipe = MongoDBDAO.getInstance().findRecipe(recommandation);
            result.append(recommandation).append("\t\t").append(recipe.get("name")).append("\n");
        }
        return result.toString();
    }
}
