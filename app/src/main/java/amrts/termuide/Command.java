package amrts.termuide;

public class Command
{
    String title;
    String command;
    String args;
    org.json.JSONObject edit;
    Lsp.Incoming origin;
    String path;
    long revision;
    int start, end;
}
