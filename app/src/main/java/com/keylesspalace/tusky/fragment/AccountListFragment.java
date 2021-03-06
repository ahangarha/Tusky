/* Copyright 2017 Andrew Dawson
 *
 * This file is a part of Tusky.
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation; either version 3 of the
 * License, or (at your option) any later version.
 *
 * Tusky is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even
 * the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General
 * Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with Tusky; if not,
 * see <http://www.gnu.org/licenses>. */

package com.keylesspalace.tusky.fragment;

import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.google.android.material.snackbar.Snackbar;
import androidx.recyclerview.widget.DividerItemDecoration;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import com.keylesspalace.tusky.AccountActivity;
import com.keylesspalace.tusky.R;
import com.keylesspalace.tusky.adapter.AccountAdapter;
import com.keylesspalace.tusky.adapter.BlocksAdapter;
import com.keylesspalace.tusky.adapter.FollowAdapter;
import com.keylesspalace.tusky.adapter.FollowRequestsAdapter;
import com.keylesspalace.tusky.adapter.MutesAdapter;
import com.keylesspalace.tusky.di.Injectable;
import com.keylesspalace.tusky.entity.Account;
import com.keylesspalace.tusky.entity.Relationship;
import com.keylesspalace.tusky.interfaces.AccountActionListener;
import com.keylesspalace.tusky.network.MastodonApi;
import com.keylesspalace.tusky.util.HttpHeaderLink;
import com.keylesspalace.tusky.util.ThemeUtils;
import com.keylesspalace.tusky.view.EndlessOnScrollListener;

import java.util.List;

import javax.inject.Inject;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class AccountListFragment extends BaseFragment implements AccountActionListener,
        Injectable {
    private static final String TAG = "AccountList"; // logging tag

    public AccountListFragment() {
    }

    public enum Type {
        FOLLOWS,
        FOLLOWERS,
        BLOCKS,
        MUTES,
        FOLLOW_REQUESTS,
    }

    @Inject
    public MastodonApi api;

    private Type type;
    private String accountId;
    private LinearLayoutManager layoutManager;
    private RecyclerView recyclerView;
    private EndlessOnScrollListener scrollListener;
    private AccountAdapter adapter;
    private boolean fetching = false;
    private String bottomId;

    public static AccountListFragment newInstance(Type type) {
        Bundle arguments = new Bundle();
        AccountListFragment fragment = new AccountListFragment();
        arguments.putSerializable("type", type);
        fragment.setArguments(arguments);
        return fragment;
    }

    public static AccountListFragment newInstance(Type type, String accountId) {
        Bundle arguments = new Bundle();
        AccountListFragment fragment = new AccountListFragment();
        arguments.putSerializable("type", type);
        arguments.putString("accountId", accountId);
        fragment.setArguments(arguments);
        return fragment;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Bundle arguments = getArguments();
        type = (Type) arguments.getSerializable("type");
        accountId = arguments.getString("accountId");
        api = null;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {

        View rootView = inflater.inflate(R.layout.fragment_account_list, container, false);

        Context context = getContext();
        recyclerView = rootView.findViewById(R.id.recycler_view);
        recyclerView.setHasFixedSize(true);
        layoutManager = new LinearLayoutManager(context);
        recyclerView.setLayoutManager(layoutManager);
        DividerItemDecoration divider = new DividerItemDecoration(
                context, layoutManager.getOrientation());
        Drawable drawable = ThemeUtils.getDrawable(context, R.attr.status_divider_drawable,
                R.drawable.status_divider_dark);
        divider.setDrawable(drawable);
        recyclerView.addItemDecoration(divider);
        scrollListener = null;
        if (type == Type.BLOCKS) {
            adapter = new BlocksAdapter(this);
        } else if (type == Type.MUTES) {
            adapter = new MutesAdapter(this);
        } else if (type == Type.FOLLOW_REQUESTS) {
            adapter = new FollowRequestsAdapter(this);
        } else {
            adapter = new FollowAdapter(this);
        }
        recyclerView.setAdapter(adapter);


        return rootView;
    }

    @Override
    public void onActivityCreated(@Nullable Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
        // Just use the basic scroll listener to load more accounts.
        scrollListener = new EndlessOnScrollListener(layoutManager) {
            @Override
            public void onLoadMore(int totalItemsCount, RecyclerView view) {
                AccountListFragment.this.onLoadMore();
            }
        };

        recyclerView.addOnScrollListener(scrollListener);

        fetchAccounts(null);

    }

    @Override
    public void onViewAccount(String id) {
        Context context = getContext();
        if(context != null) {
            Intent intent = AccountActivity.getIntent(context, id);
            startActivity(intent);
        }
    }

    @Override
    public void onMute(final boolean mute, final String id, final int position) {
        Callback<Relationship> callback = new Callback<Relationship>() {
            @Override
            public void onResponse(@NonNull Call<Relationship> call, @NonNull Response<Relationship> response) {
                if (response.isSuccessful()) {
                    onMuteSuccess(mute, id, position);
                } else {
                    onMuteFailure(mute, id);
                }
            }

            @Override
            public void onFailure(@NonNull Call<Relationship> call, @NonNull Throwable t) {
                onMuteFailure(mute, id);
            }
        };

        Call<Relationship> call;
        if (!mute) {
            call = api.unmuteAccount(id);
        } else {
            call = api.muteAccount(id);
        }
        callList.add(call);
        call.enqueue(callback);
    }

    private void onMuteSuccess(boolean muted, final String id, final int position) {
        if (muted) {
            return;
        }
        final MutesAdapter mutesAdapter = (MutesAdapter) adapter;
        final Account unmutedUser = mutesAdapter.removeItem(position);
        View.OnClickListener listener = v -> {
            mutesAdapter.addItem(unmutedUser, position);
            onMute(true, id, position);
        };
        Snackbar.make(recyclerView, R.string.confirmation_unmuted, Snackbar.LENGTH_LONG)
                .setAction(R.string.action_undo, listener)
                .show();
    }

    private void onMuteFailure(boolean mute, String id) {
        String verb;
        if (mute) {
            verb = "mute";
        } else {
            verb = "unmute";
        }
        Log.e(TAG, String.format("Failed to %s account id %s", verb, id));
    }

    @Override
    public void onBlock(final boolean block, final String id, final int position) {
        Callback<Relationship> cb = new Callback<Relationship>() {
            @Override
            public void onResponse(@NonNull Call<Relationship> call, @NonNull Response<Relationship> response) {
                if (response.isSuccessful()) {
                    onBlockSuccess(block, id, position);
                } else {
                    onBlockFailure(block, id);
                }
            }

            @Override
            public void onFailure(@NonNull Call<Relationship> call, @NonNull Throwable t) {
                onBlockFailure(block, id);
            }
        };

        Call<Relationship> call;
        if (!block) {
            call = api.unblockAccount(id);
        } else {
            call = api.blockAccount(id);
        }
        callList.add(call);
        call.enqueue(cb);
    }

    private void onBlockSuccess(boolean blocked, final String id, final int position) {
        if (blocked) {
            return;
        }
        final BlocksAdapter blocksAdapter = (BlocksAdapter) adapter;
        final Account unblockedUser = blocksAdapter.removeItem(position);
        View.OnClickListener listener = v -> {
            blocksAdapter.addItem(unblockedUser, position);
            onBlock(true, id, position);
        };
        Snackbar.make(recyclerView, R.string.confirmation_unblocked, Snackbar.LENGTH_LONG)
                .setAction(R.string.action_undo, listener)
                .show();
    }

    private void onBlockFailure(boolean block, String id) {
        String verb;
        if (block) {
            verb = "block";
        } else {
            verb = "unblock";
        }
        Log.e(TAG, String.format("Failed to %s account id %s", verb, id));
    }

    @Override
    public void onRespondToFollowRequest(final boolean accept, final String accountId,
                                         final int position) {

        Callback<Relationship> callback = new Callback<Relationship>() {
            @Override
            public void onResponse(@NonNull Call<Relationship> call, @NonNull Response<Relationship> response) {
                if (response.isSuccessful()) {
                    onRespondToFollowRequestSuccess(position);
                } else {
                    onRespondToFollowRequestFailure(accept, accountId);
                }
            }

            @Override
            public void onFailure(@NonNull Call<Relationship> call, @NonNull Throwable t) {
                onRespondToFollowRequestFailure(accept, accountId);
            }
        };

        Call<Relationship> call;
        if (accept) {
            call = api.authorizeFollowRequest(accountId);
        } else {
            call = api.rejectFollowRequest(accountId);
        }
        callList.add(call);
        call.enqueue(callback);
    }

    private void onRespondToFollowRequestSuccess(int position) {
        FollowRequestsAdapter followRequestsAdapter = (FollowRequestsAdapter) adapter;
        followRequestsAdapter.removeItem(position);
    }

    private void onRespondToFollowRequestFailure(boolean accept, String accountId) {
        String verb;
        if (accept) {
            verb = "accept";
        } else {
            verb = "reject";
        }
        String message = String.format("Failed to %s account id %s.", verb, accountId);
        Log.e(TAG, message);
    }

    private Call<List<Account>> getFetchCallByListType(Type type, String fromId) {
        switch (type) {
            default:
            case FOLLOWS:
                return api.accountFollowing(accountId, fromId, null, null);
            case FOLLOWERS:
                return api.accountFollowers(accountId, fromId, null, null);
            case BLOCKS:
                return api.blocks(fromId, null, null);
            case MUTES:
                return api.mutes(fromId, null, null);
            case FOLLOW_REQUESTS:
                return api.followRequests(fromId, null, null);
        }
    }

    private void fetchAccounts(String id) {
        if (fetching) {
            return;
        }
        fetching = true;

        if (id != null) {
            recyclerView.post(() -> adapter.setBottomLoading(true));
        }

        Callback<List<Account>> cb = new Callback<List<Account>>() {
            @Override
            public void onResponse(@NonNull Call<List<Account>> call, @NonNull Response<List<Account>> response) {
                if (response.isSuccessful()) {
                    String linkHeader = response.headers().get("Link");
                    onFetchAccountsSuccess(response.body(), linkHeader);
                } else {
                    onFetchAccountsFailure(new Exception(response.message()));
                }
            }

            @Override
            public void onFailure(@NonNull Call<List<Account>> call, @NonNull Throwable t) {
                onFetchAccountsFailure((Exception) t);
            }
        };
        Call<List<Account>> listCall = getFetchCallByListType(type, id);
        callList.add(listCall);
        listCall.enqueue(cb);
    }

    private void onFetchAccountsSuccess(List<Account> accounts, String linkHeader) {
        adapter.setBottomLoading(false);


        List<HttpHeaderLink> links = HttpHeaderLink.parse(linkHeader);
        HttpHeaderLink next = HttpHeaderLink.findByRelationType(links, "next");
        String fromId = null;
        if (next != null) {
            fromId = next.uri.getQueryParameter("max_id");
        }
        if (adapter.getItemCount() > 1) {
            adapter.addItems(accounts);
        } else {
            adapter.update(accounts);
        }

        bottomId = fromId;

        fetching = false;

        adapter.setBottomLoading(false);
    }

    private void onFetchAccountsFailure(Exception exception) {
        fetching = false;
        Log.e(TAG, "Fetch failure: " + exception.getMessage());
    }

    private void onLoadMore() {
        if(bottomId == null) {
            return;
        }
        fetchAccounts(bottomId);
    }

}
