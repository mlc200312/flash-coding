package com.flashfin.i18n.tool.dictionary;

public interface DictionaryClient {

    RemoteDictionary fetch(String os, String language);
}
