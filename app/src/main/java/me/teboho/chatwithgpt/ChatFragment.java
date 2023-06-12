package me.teboho.chatwithgpt;

import android.content.Context;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import me.teboho.chatwithgpt.databinding.FragmentChatBinding;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * A simple {@link Fragment} subclass.
 * Use the {@link ChatFragment#newInstance} factory method to
 * create an instance of this fragment.
 */
public class ChatFragment extends Fragment {

    // TODO: Rename parameter arguments, choose names that match
    // the fragment initialization parameters, e.g. ARG_ITEM_NUMBER
    private static final String ARG_PARAM1 = "param1";
    private static final String ARG_PARAM2 = "param2";
    // TODO: Rename and change types of parameters
    private String mParam1;
    private String mParam2;

    // exposing the API key is not a good practice, but this is just a demo
    final static String OPENAI_API_KEY = BuildConfig.apikey;
    final static String model = "gpt-3.5-turbo";
    final static String url = "https://api.openai.com/v1/chat/completions";
    FragmentChatBinding binding;
    MainViewModel viewModel;
    Thread t;

    public ChatFragment() {
        // Required empty public constructor
    }

    /**
     * Use this factory method to create a new instance of
     * this fragment using the provided parameters.
     *
     * @param param1 Parameter 1.
     * @param param2 Parameter 2.
     * @return A new instance of fragment ChatFragment.
     */
    // TODO: Rename and change types and number of parameters
    public static ChatFragment newInstance(String param1, String param2) {
        ChatFragment fragment = new ChatFragment();
        Bundle args = new Bundle();
        args.putString(ARG_PARAM1, param1);
        args.putString(ARG_PARAM2, param2);
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getArguments() != null) {
            mParam1 = getArguments().getString(ARG_PARAM1);
            mParam2 = getArguments().getString(ARG_PARAM2);
        }
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        // Inflate the layout for this fragment

        binding = FragmentChatBinding.inflate(inflater, container, false);

        // handle the buttons
        binding.btnSend.setOnClickListener(this::handleSendButton);
        binding.btnReset.setOnClickListener(this::handleResetButton);

        View view = binding.getRoot();

        // get the viewmodel
        viewModel = new ViewModelProvider(requireActivity()).get(MainViewModel.class);

        return view;
    }
    ChatsAdapter adapter;
    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        adapter = new ChatsAdapter(viewModel);
        binding.chatsRecyclerView.setAdapter(adapter);
        binding.chatsRecyclerView.setLayoutManager(new LinearLayoutManager(getContext()));

//        viewModel.getInHistory().observe(getViewLifecycleOwner(), inChats -> {
//            // update the recyclerview
//            adapter.notifyItemRangeChanged(0, inChats.size());
//        });
//        viewModel.getOutHistory().observe(getViewLifecycleOwner(), outChats -> {
//            // update the recyclerview
//            adapter.notifyItemRangeChanged(0, outChats.size());
//        });

        // scroll to bottom of recyclerview
        binding.chatsRecyclerView.scrollToPosition(Objects.requireNonNull(binding.chatsRecyclerView.getAdapter()).getItemCount());
    }

    public void handleResetButton(View view){
        System.out.println("Reset button clicked");
        if (t != null && t.isAlive()) {
            t.interrupt();
            t = null;
        }

        binding.progressBar.setVisibility(View.GONE);
        binding.chatInput.setText("");
        int size = viewModel.getInHistory().getValue().size();
        adapter.viewModel.getChatInput().setValue("");
        adapter.viewModel.getChatOutput().setValue("");
        adapter.viewModel.getInHistory().getValue().clear();
        adapter.viewModel.getOutHistory().getValue().clear();

        viewModel.getInHistory().getValue().clear();
        viewModel.getOutHistory().getValue().clear();

        System.gc();

        getActivity().recreate();

        adapter.notifyItemRangeRemoved(0, size);
    }

    private String prepMessageWithoutHistory(String chat) {
        String json = "{\"model\": \""+ model + "\",\n \"messages\": ["
                +  "{\"role\": \"user\", \"content\": \"" + chat +"\"}]}";

        return json;
    }

    private String prepMessageWithHistory(String chat) {
        String json = "{\"model\": \""+ model +"\",\n  \"messages\": [";

        ArrayList<String> inHistory = viewModel.getInHistory().getValue();
        ArrayList<String> outHistory = viewModel.getOutHistory().getValue();
        for (int i=0; i < inHistory.size(); i++) {
            json += "{\"role\": \"user\", \"content\": \"" + complyJSON(inHistory.get(i)) +"\"}, ";
            json += "{\"role\": \"assistant\", \"content\": \"" + complyJSON(outHistory.get(i)) +"\"}";
            // add comma if not last element
            if (i != inHistory.size() - 1) {
                json += ",";
            }
        }
        json += ",{\"role\": \"user\", \"content\": \"" + chat +"\"}";
        json += "]}";

        return json;
    }

    /**
     * This method is called when the send button is clicked
     * @param view the view that was clicked
     * @author teboho
     */
    public void handleSendButton(View view) {
        // Show loading indicator using animation
        binding.progressBar.setVisibility(View.VISIBLE);

        // clear error
        binding.chatInput.setError(null);
        // get out of the chat input and remove the keyboard
        binding.chatInput.clearFocus();
        InputMethodManager imm = (InputMethodManager) getActivity().getSystemService(Context.INPUT_METHOD_SERVICE);
        imm.hideSoftInputFromWindow(binding.chatInput.getWindowToken(), 0);

        t = new Thread(() -> {
            final MediaType JSON
                    = MediaType.get("application/json; charset=utf-8");

            OkHttpClient.Builder builder = new OkHttpClient.Builder();
            builder.connectTimeout(5, TimeUnit.MINUTES) // connect timeout
                    .writeTimeout(5, TimeUnit.MINUTES) // write timeout
                    .readTimeout(5, TimeUnit.MINUTES); // read timeout

            OkHttpClient client = builder.build();

            String chat = binding.chatInput.getText().toString();
            if (chat.isEmpty() || chat == null) {
                getActivity().runOnUiThread(() -> {
                    binding.chatInput.setError("Please enter something");
                    binding.progressBar.setVisibility(View.GONE);
                });
                // end the thread
                t.interrupt();
                return;
            } else {
                storeInput(chat);
            }

            // because we are about to send it to the server, we need to comply with the JSON standard
            chat = complyJSON(chat);

            String json = "";

            if (viewModel.getInHistory().getValue().size() > 0) {
                json = prepMessageWithHistory(chat);
            }
            else {
                json = prepMessageWithoutHistory(chat);
            }

            if (json.length() > 3900) {
                getActivity().runOnUiThread(() -> {
                    MainActivity.showSnackbar("History data too large, will only not use it.\nTap Reset to clear history");
                });
                json = prepMessageWithoutHistory(chat);
            }

            System.out.println(json);

            RequestBody body = RequestBody.create(json, JSON);
            Request request = new Request.Builder()
                    .addHeader("Authorization", "Bearer " + OPENAI_API_KEY)
                    .url(url)
                    .post(body)
                    .build();
            try (Response response = client.newCall(request).execute()) {
                String res = response.body().string();
                System.out.println("Response: \n" + res);
                if (!response.isSuccessful()) {
                    System.out.println("Response code: " + response.code());
                    getActivity().runOnUiThread(() -> {
                        if (response.code() == 429)
                            MainActivity.showSnackbar("Try again in a 60 seconds");
                        else
                            MainActivity.showSnackbar("There was an error, please try again");
                        binding.chatInput.setError("There was an error, please try again");
                        // Hide loading indicator
                        binding.progressBar.setVisibility(View.GONE);
                    });
                    // end the thread
                     t.interrupt();
                    return;
                }
                else
                    getActivity().runOnUiThread(() -> binding.chatInput.setError(null));

                getActivity().runOnUiThread(() -> binding.progressBar.setVisibility(View.GONE));

                String message = processResponse(res);

                storeOutput(message);
                // empty the chat input
                getActivity().runOnUiThread(() -> binding.chatInput.setText(""));
            } catch (IOException e) {
                getActivity().runOnUiThread(() -> binding.chatInput.setError("Something went wrong with the internet request/response"));
                getActivity().runOnUiThread(() -> MainActivity.showSnackbar("Something went wrong with the internet request/response" + e.getMessage()));
                e.printStackTrace();
            }
        }, "chat");
        t.start();
    }

    private String processResponse(String jsonResponse) {
        // Break down the response json
        ObjectMapper mapper = new ObjectMapper();
        JsonNode rootNode = null;
        String message = "";
        try {
            rootNode = mapper.readTree(jsonResponse);

            // Get the choices
            String id = rootNode.get("id").asText();
            String object = rootNode.get("object").asText();
            String created = rootNode.get("created").asText();
            String model = rootNode.get("model").asText();
            String choices = rootNode.get("choices").asText();
            System.out.println(choices);
            JsonNode choicesNode = rootNode.get("choices");
            int index = choicesNode.get(0).get("index").asInt();
            message = choicesNode.get(0).get("message").get("content").asText();
            String finishReason = choicesNode.get(0).get("finish_reason").asText();
        } catch (JsonProcessingException e) {
            getActivity().runOnUiThread(() ->
                    MainActivity.showSnackbar("Something went wrong with the internet request/response" + e.getMessage())
            );
            throw new RuntimeException(e);
        }
        return message;
    }

    /**
     * This method stores the input in the view model
     * @param input the input to store in the view model
     */
    private void storeInput(String input) {
        getActivity().runOnUiThread(() -> viewModel.getChatInput().setValue(input));
    }

    /**
     * This method stores the output in the view model
     * @param output the output to store in the view model
     */
    private void storeOutput(String output) {
        getActivity().runOnUiThread(() -> {
            viewModel.getChatOutput().setValue(output);

            // Store the chat in the chat history
            viewModel.getInHistory().getValue().add(viewModel.getChatInput().getValue());
            viewModel.getOutHistory().getValue().add(output);

            binding.chatsRecyclerView.getAdapter().notifyItemInserted(binding.chatsRecyclerView.getAdapter().getItemCount());

            // Scroll to the bottom of the recycler view
            binding.chatsRecyclerView.smoothScrollToPosition(binding.chatsRecyclerView.getAdapter().getItemCount());
        });
    }

    /**
     *
     * @param str the string to make json compliant
     * @return the json compliant string
     */
    private String complyJSON(String str) {
        // make string json compliant
        String escaped = str.replace("\"", "\\"+"\"");
        escaped = escaped.replace("\n", "\\" + "n");
        escaped = escaped.replace("\r", "\\r");
        escaped = escaped.replace("\t", "\\t");
        escaped = escaped.replace("\b", "\\b");
        escaped = escaped.replace("\f", "\\f");
        escaped = escaped.replace("/", "\\/");

        return escaped;
    }
}