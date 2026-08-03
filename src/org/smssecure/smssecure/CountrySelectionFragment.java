package org.smssecure.smssecure;

import android.content.Context;
import android.os.Bundle;
import androidx.annotation.NonNull;
import androidx.fragment.app.ListFragment;
import androidx.loader.app.LoaderManager;
import androidx.loader.content.Loader;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.SimpleAdapter;


import org.smssecure.smssecure.database.loaders.CountryListLoader;

import java.util.ArrayList;
import java.util.Map;

public class CountrySelectionFragment extends ListFragment implements LoaderManager.LoaderCallbacks<ArrayList<Map<String, String>>> {

  private EditText countryFilter;
  private CountrySelectedListener listener;

  @Override
  public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle bundle) {
    return inflater.inflate(R.layout.country_selection_fragment, container, false);
  }

  @Override
  public void onViewCreated(@NonNull View view, Bundle bundle) {
    super.onViewCreated(view, bundle);
    this.countryFilter = (EditText)view.findViewById(R.id.country_search);
    this.countryFilter.addTextChangedListener(new FilterWatcher());
    LoaderManager.getInstance(this).initLoader(0, null, this).forceLoad();
  }

  @Override
  public void onAttach(@NonNull Context context) {
    super.onAttach(context);
    this.listener = (CountrySelectedListener) context;
  }

  @Override
  public void onListItemClick(ListView listView, View view, int position, long id) {
    Map<?, ?> item = (Map<?, ?>)this.getListAdapter().getItem(position);
    if (this.listener != null) {
      String countryName = (String)item.get("country_name");
      String countryCode = (String)item.get("country_code");
      this.listener.countrySelected(countryName,
                                    Integer.parseInt(countryCode.substring(1)));
    }
  }

  @Override
  public Loader<ArrayList<Map<String, String>>> onCreateLoader(int arg0, Bundle arg1) {
    return new CountryListLoader(getActivity());
  }

  @Override
  public void onLoadFinished(Loader<ArrayList<Map<String, String>>> loader,
                             ArrayList<Map<String, String>> results)
  {
    String[] from = {"country_name", "country_code"};
    int[] to      = {R.id.country_name, R.id.country_code};
    this.setListAdapter(new SimpleAdapter(getActivity(), results, R.layout.country_list_item, from, to));

    if (this.countryFilter != null && this.countryFilter.getText().length() != 0) {
      ((SimpleAdapter)getListAdapter()).getFilter().filter(this.countryFilter.getText().toString());
    }
  }

  @Override
  public void onLoaderReset(Loader<ArrayList<Map<String, String>>> arg0) {
    this.setListAdapter(null);
  }

  public interface CountrySelectedListener {
    public void countrySelected(String countryName, int countryCode);
  }

  private class FilterWatcher implements TextWatcher {

    @Override
    public void afterTextChanged(Editable s) {
      if (getListAdapter() != null) {
        ((SimpleAdapter)getListAdapter()).getFilter().filter(s.toString());
      }
    }

    @Override
    public void beforeTextChanged(CharSequence s, int start, int count, int after) {
    }

    @Override
    public void onTextChanged(CharSequence s, int start, int before, int count) {
    }
  }
}
